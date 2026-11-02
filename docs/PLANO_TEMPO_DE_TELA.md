# Plano — Métricas confiáveis de tempo de tela desbloqueada (Android)

> Objetivo: medir, com a maior precisão possível, **quanto tempo por dia o telefone fica
> desbloqueado e em uso**, guardar o histórico por data e calcular médias confiáveis
> (diária, semanal, mensal, dias úteis × fim de semana, tendência).
>
> **Toda data e hora gravada pelo app vem da internet** (hora oficial sincronizada), não do
> relógio do aparelho, que pode estar errado ou ser alterado pelo usuário (Seção 3).

---

## 1. Definição exata do que será medido

Antes de qualquer código, a métrica precisa ser definida sem ambiguidade — é isso que
garante números consistentes.

### 1.1 Métrica principal: **Tempo de tela desbloqueada (TTD)**

```
TTD = soma das durações dos intervalos em que
      (tela em estado INTERATIVO)  E  (tela de bloqueio/keyguard OCULTA)
```

| Situação | Conta no TTD? | Motivo |
|---|---|---|
| Telefone desbloqueado, usando apps / home | **Sim** | uso real |
| Tela acende por notificação, mas continua bloqueada | Não | keyguard visível |
| Always-On Display (AOD) / ambient display | Não | estado não interativo |
| Protetor de tela (dream) durante carregamento | Não | estado não interativo |
| Câmera aberta pela tela de bloqueio / chamada sobre a tela bloqueada | Não (padrão) | keyguard continua "mostrado" (ocluído) |
| Bloqueio com atraso ("bloquear 30 s após apagar"): tela apagada sem keyguard | Não | tela não interativa |
| Telefone sem bloqueio de tela ("Nenhum") | Sim, enquanto a tela estiver interativa | não existe keyguard |
| Chamada com o telefone no ouvido (sensor de proximidade apaga o display) | Sim (padrão, configurável) | o sistema continua "interativo" — ver decisão D1 |
| Vídeo assistido sem tocar na tela | Sim | tela desbloqueada e ligada |
| "Tempo de cauda" até a tela apagar por inatividade (ex.: 30 s) | Sim (padrão) | ver decisão D6 |

### 1.2 Métricas secundárias (derivadas dos mesmos eventos)

- **Tempo de tela ligada total** (interativo, bloqueado ou não) — para comparar com o
  "screen on time" da bateria.
- **Desbloqueios por dia** (transições bloqueado → desbloqueado).
- **Sessões**: quantidade, duração média, **mediana**, maior sessão.
- **Intervalo médio entre desbloqueios**.
- **Primeiro desbloqueio** e **último bloqueio** do dia (com a hora da internet).
- **Distribuição por hora do dia** (mapa de calor 24 × 7).
- (Fase posterior) **Tempo por app** via eventos `ACTIVITY_RESUMED/PAUSED`, apenas como
  detalhamento — nunca como base do total.

### 1.3 Por que não copiar o número do Bem-estar Digital

O Bem-estar Digital (e equivalentes de fabricantes) soma o **tempo de apps em primeiro
plano**; a métrica aqui é **do dispositivo** (desbloqueado + tela interativa). Os números
vão diferir em alguns minutos por dia, e isso é esperado. A comparação com o Bem-estar
Digital será usada só como verificação de sanidade (Seção 11).

---

## 2. Pesquisa: fontes de dados de uso no Android

### 2.1 `UsageStatsManager.queryEvents()` — **fonte principal escolhida**

Eventos registrados **pelo próprio sistema**, independentemente de o nosso app estar
rodando:

| Constante | Valor | API | Significado |
|---|---|---|---|
| `SCREEN_INTERACTIVE` | 15 | 28 | tela entrou em estado interativo (não AOD) |
| `SCREEN_NON_INTERACTIVE` | 16 | 28 | tela apagou ou foi para AOD/ambient |
| `KEYGUARD_SHOWN` | 17 | 28 | tela de bloqueio mostrada (mesmo com tela apagada) |
| `KEYGUARD_HIDDEN` | 18 | 28 | tela de bloqueio oculta (desbloqueio) |
| `DEVICE_SHUTDOWN` | 26 | 29 | desligamento (timestamp = última gravação do banco) |
| `DEVICE_STARTUP` | 27 | 29 | inicialização do runtime |
| `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` | 1 / 2 | 29 | app em primeiro plano (detalhamento por app) |

O que foi confirmado lendo o código-fonte do AOSP (`frameworks/base`, branch `main`):

1. **Origem dos eventos de tela e keyguard** — `ActivityManagerService.reportGlobalUsageEvent()`
   emite `SCREEN_INTERACTIVE` quando a *wakefulness* vira `AWAKE` e `SCREEN_NON_INTERACTIVE`
   caso contrário (dormindo, *dozing* ou *dreaming*). O keyguard é reportado por
   `ActivityTaskManagerService.setLockScreenShown()` somente **quando o estado muda**. Os
   eventos têm pacote `"android"` e são enviados ao usuário atual **e aos seus perfis**
   (perfil de trabalho), então não há contagem dupla dentro do mesmo app.
2. **Retenção** — `UsageStatsDatabase.prune()` apaga arquivos diários com mais de
   **10 dias** (e limita a 100 arquivos). Os eventos brutos ficam só nos arquivos diários.
   A documentação oficial diz apenas "alguns dias". **Consequência: o app precisa copiar os
   eventos para o próprio banco antes que expirem.** Usaremos uma margem de segurança de
   **7 dias** (alguns fabricantes podem reter menos).
3. **Gravação em disco a cada 20 min** (`FLUSH_INTERVAL = TWENTY_MINUTES`). Em um desligamento
   normal o banco é gravado antes; ao segurar o botão de energia por 3,5 s também. Em uma
   **queda abrupta** (bateria acabou, travamento) perdem-se até ~20 min de eventos. No
   boot seguinte o sistema insere um `DEVICE_SHUTDOWN` sintético com o timestamp da última
   gravação.
4. **Os timestamps dos eventos usam o relógio do aparelho** (`System.currentTimeMillis()`).
   Internamente, o sistema registra o evento com `elapsedRealtime` e converte usando um par
   de referência (`mRealTimeSnapshot`/`mSystemTimeSnapshot`). **Se o relógio do aparelho
   estiver errado, todos os eventos ficam com a hora errada** — por isso a Seção 3 converte
   cada evento para a hora da internet.
5. **Correção de relógio** — quando o relógio muda mais de 2 s
   (`TIME_CHANGE_THRESHOLD_MILLIS`), o sistema **desloca todo o histórico armazenado pela
   mesma diferença** (`UsageStatsDatabase.onTimeChanged()` renomeia os arquivos). Os
   timestamps de eventos já lidos por nós podem, portanto, voltar **diferentes** numa nova
   consulta. Isso afeta a deduplicação e a conversão para a hora real (Seções 3.4 e 6.3).
6. **Os buckets agregados do sistema não servem como fonte de dados diária**: o bucket
   diário começa no instante da última virada (`UnixCalendar`, sem fuso horário), e não à
   meia-noite local. Além disso, o `queryEventStats()` informa a duração de
   `KEYGUARD_HIDDEN` incluindo tempo de tela **apagada** (bloqueio com atraso). Portanto
   **não dá para obter a interseção "interativo E desbloqueado" pelos agregados**;
   calcularemos tudo a partir dos eventos brutos.
7. A partir do Android 11, `queryEvents` retorna vazio enquanto o usuário não desbloquear o
   aparelho após o boot (`isUserUnlocked() == false`).
8. No Android 15 (API 35) existe `queryEvents(UsageEventsQuery)` com
   `setEventTypes(...)`, que filtra no sistema e é bem mais eficiente. Nas versões
   anteriores, filtra-se durante a iteração.
9. Os eventos ainda não gravados em disco (em memória) **são retornados** pela consulta, ou
   seja, os dados são praticamente em tempo real.

**Vantagens:** retroativo (instalou hoje → já tem cerca de 10 dias de histórico), não exige
serviço rodando o tempo todo, resiste a o app ser encerrado pelo sistema/fabricante e tem
baixo consumo de bateria.

**Requisito:** a permissão especial `PACKAGE_USAGE_STATS` ("Acesso ao uso"), concedida pelo
usuário nas Configurações.

### 2.2 Estado instantâneo — `PowerManager.isInteractive()` + `KeyguardManager.isKeyguardLocked()`

Serve para fechar "virtualmente" a sessão em andamento (contador "hoje até agora") e para
desambiguar o estado inicial. Não é usado como histórico.

### 2.3 Broadcasts `ACTION_SCREEN_ON/OFF` e `ACTION_USER_PRESENT` — **descartados como fonte principal**

Não estão na lista de broadcasts implícitos isentos, então só funcionam com registro
dinâmico, o que exige processo vivo (serviço em primeiro plano). Os problemas: o app é
encerrado por fabricantes, há gasto de bateria, regras de tipo de serviço em primeiro plano
(Android 14+) e revisão da Play Store, e **perde eventos quando o processo morre**. Ficam,
no máximo, como verificação cruzada opcional no futuro.

### 2.4 Broadcasts úteis e isentos (registráveis no manifest)

`BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`. Servem para disparar a ingestão, a
sincronização da hora pela internet e o registro de mudanças de relógio/fuso.

### 2.5 Descartados

- **AccessibilityService**: invasivo, a política da Play Store restringe o uso e é
  desnecessário.
- **BatteryStats / `dumpsys`**: exigem permissões de sistema. Só via `adb`, para
  validação.

---

## 3. Data e hora pela internet (relógio confiável)

### 3.1 Por que

O relógio do aparelho (`System.currentTimeMillis()`) pode estar adiantado/atrasado (sem
"data e hora automáticas"), ser alterado manualmente (inclusive para "burlar" o
contador do dia) ou estar errado logo após o boot. Como **todos os eventos do sistema são
carimbados com esse relógio**, um relógio errado faria o uso cair no **dia errado**, deixaria
o "primeiro desbloqueio" e o mapa por hora errados e quebraria as médias.

**Regra do app:** nenhum valor de data/hora é gravado ou exibido com base no relógio do
aparelho. Tudo é convertido para a **hora UTC obtida pela internet** e, depois, para a data
local pelo fuso de referência (Seção 8).

### 3.2 Fontes de hora pela internet (em cascata)

| Prioridade | Fonte | Como | Precisão típica | Observações |
|---|---|---|---|---|
| 1 | **Google TrustedTime** (Play services, `com.google.android.gms:play-services-time:16.0.1+`) | `TrustedTime.createClient(ctx)` → `client.getLatestTimeSignal()?.computeCurrentInstant()` → `getInstantMillis()` + `getEstimatedErrorMillis()` | dezenas de ms, **com estimativa de erro** | sincroniza periodicamente com servidores do Google; não depende do relógio do aparelho; retorna `null` se não houve internet desde o boot; exige Play services |
| 2 | **`SystemClock.currentNetworkTimeClock()`** (API 33+) | `.millis()` | dezenas de ms | hora da última sincronização de rede do sistema; **não ajustável pelo usuário**; não bloqueia; lança `DateTimeException` se indisponível |
| 3 | **Cliente SNTP próprio** (UDP 123) | servidores **NTP.br** (`a.st1.ntp.br` … `e.st1.ntp.br`, ligados aos relógios de césio da Hora Legal Brasileira) e `time.google.com` | ~10–50 ms | funciona sem Play services (ex.: Huawei); algumas redes bloqueiam UDP 123 |
| 4 | **Cabeçalho HTTP `Date`** via HTTPS (fallback) | `HEAD https://www.google.com`, usar o ponto médio do RTT | ~0,5–1 s | resolução de 1 s; autenticado por TLS; falha se o relógio do aparelho estiver tão errado que invalide o certificado |
| — | Nenhuma disponível | usar o último desvio conhecido da mesma **época de relógio** (3.4); se não houver, relógio do aparelho com marca `HORA_NAO_VERIFICADA` | — | corrigido **retroativamente** quando a internet voltar (3.5) |

Verificação cruzada: quando duas fontes estiverem disponíveis e divergirem em mais de 2 s,
vale a de **menor erro estimado**, e a divergência é registrada (`time_sync.disagreement`).

Cálculo SNTP (fonte 3), com `t0` (envio) e `t3` (recebimento) medidos pelo relógio
monotônico `elapsedRealtimeNanos`, e `t1`/`t2` sendo os carimbos do servidor:

```
θ = ((t1 − t0) + (t2 − t3)) / 2     → hora_real = elapsedRealtime + θ   (vira a âncora, 3.3)
erro ≈ RTT / 2 = ((t3 − t0) − (t2 − t1)) / 2
```

Fazer de 4 a 8 consultas, escolher a de **menor RTT** e descartar respostas com
`stratum = 0`, *Kiss-o'-Death* ou `leap = 3`.

### 3.3 Âncora monotônica: a hora real sem depender do relógio do aparelho

Cada sincronização bem-sucedida grava um par:

```
(elapsedRealtime_ms, boot_count)  ↔  hora_real_utc_ms   (+ relógio_do_aparelho_ms, erro_ms, fonte)
```

`elapsedRealtime` é monotônico, conta inclusive o sono profundo e **não muda quando o
usuário altera o relógio**. A partir da âncora:

```
agora_real          = hora_real_da_âncora + (elapsedRealtime_agora − elapsedRealtime_da_âncora)
desvio_do_aparelho  = relógio_do_aparelho − hora_real      (no instante da âncora)
```

`BOOT_COUNT` (`Settings.Global.BOOT_COUNT`) identifica o boot, porque o `elapsedRealtime`
zera a cada reinício. O erro estimado cresce com a idade da âncora (deriva do cristal,
~±50 ppm ≈ ±4 s/dia), por isso há **ressincronização periódica**.

### 3.4 Converter os eventos do sistema para hora real

Os eventos chegam com a hora do aparelho. Definição: **época de relógio** = período em que
a relação entre o relógio do aparelho e o `elapsedRealtime` é constante. Ela começa no boot
ou numa mudança de relógio (`TIME_SET` ou salto > 2 s detectado pela âncora).

Ponto-chave (do item 2.1-5): quando o relógio muda, o **sistema desloca todo o histórico**
para o novo referencial. Logo, **todos os eventos retornados por uma consulta estão no
referencial do relógio vigente no momento da consulta**, e a conversão correta é:

```
ts_real = ts_do_evento − desvio_do_aparelho_vigente_na_consulta
```

Procedimento em cada ingestão:
1. Obter o desvio vigente: sincronizar se não houver âncora válida na época atual ou se ela
   tiver mais de 6 h.
2. Consultar os eventos e converter **todos** com esse desvio.
3. Gravar no banco o timestamp original (`ts_device_ms`), o convertido (`ts_utc_ms`), o
   desvio, o erro e a qualidade.
4. Eventos já gravados são **imutáveis** (já estão em hora real). Como a ingestão é
   frequente, quase todos os eventos são convertidos na mesma época em que ocorreram.

Durações **não mudam** com um desvio constante (o desvio cancela na subtração); o que muda
é **em que dia e em que hora** o uso é contabilizado.

### 3.5 Qualidade da hora e correção retroativa

| Qualidade | Significado |
|---|---|
| `VERIFICADA` | desvio medido na mesma época de relógio, com erro ≤ 2 s |
| `INFERIDA` | desvio de uma época anterior aplicado a eventos do backfill, ou âncora com erro > 2 s |
| `NAO_VERIFICADA` | sem nenhuma sincronização disponível; usado o relógio do aparelho |

Quando a primeira sincronização de uma época acontece (internet voltou), os eventos
`NAO_VERIFICADA` **da mesma época** (mesmo `boot_count` e sem salto de relógio entre eles)
são reconvertidos com o novo desvio, e as sessões e os dias afetados são recalculados. Isso
é seguro porque o algoritmo é idempotente a partir dos eventos brutos.

### 3.6 Quando sincronizar

| Gatilho | Observação |
|---|---|
| `TimeSyncWorker` periódico a cada **6 h**, com restrição `NetworkType.CONNECTED` | mantém a âncora fresca |
| Antes de cada ingestão, se não houver âncora válida na época atual | garante a conversão correta |
| `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` | nova época de relógio |
| App aberto: `TrustedTimeClient.addTimeSignalListener(...)` | atualização imediata quando chega um sinal novo |
| Conectividade restabelecida após período sem internet | dispara a correção retroativa (3.5) |

### 3.7 Exemplo prático

O aparelho marca **23:58** do dia 10, mas a hora real (internet) é **00:03** do dia 11 (relógio
5 min atrasado). Um desbloqueio nesse instante é contado no **dia 11**. Se o usuário
adiantar o relógio para o dia seguinte, o tempo de uso **continua** no dia real.

### 3.8 Aviso ao usuário

Se `|desvio_do_aparelho| > 1 min`, a tela Hoje mostra: "O relógio do aparelho está X min
adiantado/atrasado — o app usa a hora oficial da internet."

---

## 4. Arquitetura

```
┌─────────────────────────── :app (Android) ────────────────────────────┐
│ UI (Compose): Hoje · Histórico · Médias · Qualidade dos dados · Ajustes│
│ Workers (WorkManager): Ingestão · TimeSync · Fechamento diário         │
│ Receivers (BOOT/TIME_SET/TIMEZONE)                                     │
│ UsageEventSource (UsageStatsManager) · Room (banco local)              │
│ NetworkTimeSources: TrustedTime · NetworkClock(API33) · SNTP · HTTPS   │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ usa
┌──────────────────────────────▼────────────────────────────────────────┐
│ :core (Kotlin puro, sem Android → testável em JVM)                    │
│  TrustedClock (âncoras, épocas, desvio, qualidade) · SntpMath         │
│  Sessionizer (máquina de estados) · DaySplitter (fuso/DST)            │
│  CoverageCalculator · StatsEngine (médias, mediana, desvio, tendência)│
└───────────────────────────────────────────────────────────────────────┘
```

- **Linguagem:** Kotlin. **UI:** Jetpack Compose. **Persistência:** Room.
  **Agendamento:** WorkManager. **Datas:** `java.time` (nativo). **Hora real:**
  `play-services-time` + `SystemClock.currentNetworkTimeClock()` + SNTP próprio (sem
  bibliotecas extras; o protocolo SNTP cabe em ~80 linhas).
- **minSdk 29** (Android 10), recomendado: garante `DEVICE_STARTUP/SHUTDOWN`. Com minSdk 28,
  o reboot teria de ser inferido por heurística. **targetSdk:** o mais recente.
- **Separar o núcleo (`:core`)** permite testar todo o algoritmo com sequências sintéticas
  de eventos e relógios simulados, sem emulador.
- **Uma única fonte de "agora"** no código: a interface `TrustedClock.now()`. É proibido
  usar `System.currentTimeMillis()`, `Instant.now()` ou `LocalDate.now()` fora do adaptador
  do relógio do aparelho (verificação por regra de lint/Detekt).

---

## 5. Modelo de dados (Room)

```text
time_sync        (id PK, elapsed_realtime_ms, boot_count,
                  device_wall_ms, true_utc_ms, device_offset_ms, error_ms,
                  source[TRUSTED_TIME|NETWORK_CLOCK|SNTP|HTTPS_DATE],
                  server, rtt_ms, disagreement_ms, ok, error)

clock_epoch      (id PK, boot_count, start_elapsed_ms, start_device_wall_ms,
                  reason[BOOT|TIME_SET|JUMP_DETECTED],
                  best_offset_ms, best_offset_error_ms, best_sync_id)

raw_event        (id PK, ts_device_ms, ts_utc_ms, offset_ms, offset_error_ms,
                  time_quality[VERIFICADA|INFERIDA|NAO_VERIFICADA], epoch_id,
                  type, pkg, cls, seq_in_batch, ingest_run_id)
                 UNIQUE(ts_device_ms, type, pkg, cls, epoch_id)
                 -- apenas tipos 15,16,17,18,26,27 (+1,2 quando houver o detalhamento por app)

ingest_run       (id PK, started_at_utc, query_begin_device, query_end_device,
                  events_read, events_new, offset_ms, ok, error,
                  elapsed_realtime_ms, boot_count, device_wall_ms)   -- âncora de relógio

clock_event      (id PK, detected_at_utc, kind[TIME_SET|TIMEZONE_CHANGED|REBOOT],
                  delta_ms, old_zone, new_zone)

zone_history     (effective_from_utc PK, zone_id)   -- fuso do aparelho ao longo do tempo

permission_log   (ts_utc, granted BOOLEAN)          -- acesso ao uso concedido/revogado

session          (id PK, start_utc, end_utc, duration_ms,           -- hora real (internet)
                  start_reason[UNLOCK|SCREEN_ON_NO_KEYGUARD],
                  end_reason[SCREEN_OFF|KEYGUARD_SHOWN|SHUTDOWN|SHUTDOWN_ESTIMATED|OPEN],
                  flags BITMASK[LONG|TIME_CHANGED|ESTIMATED_END|OUT_OF_ORDER],
                  time_quality, algo_version)

daily_summary    (local_date PK, zone_id, day_length_ms,          -- 23h/24h/25h (DST)
                  unlocked_ms, screen_on_ms, unlocks, sessions,
                  longest_session_ms, median_session_ms,
                  first_unlock_local, last_lock_local,
                  covered_ms, coverage_ratio,
                  status[COMPLETE|PARTIAL|NO_DATA|GAP],
                  time_quality, max_time_error_ms,
                  quality_flags, algo_version, computed_at_utc)

hourly_bucket    (local_date, hour 0..23, unlocked_ms, unlocks)    -- mapa de calor
```

Princípios:
- **Todos os instantes são guardados em UTC real (internet), em ms.** O timestamp original
  do aparelho é mantido só para auditoria e deduplicação.
- Datas locais só existem nas tabelas agregadas, sempre com o `zone_id` usado.
- **`algo_version`**: ao mudar o algoritmo, os resumos são recalculados a partir dos
  `raw_event`, que são a fonte da verdade.
- O histórico além de 10 dias existe **só no nosso banco**, por isso backup e exportação
  são importantes (Seção 9).

---

## 6. Ingestão (copiar eventos do sistema antes de expirarem)

### 6.1 Quando rodar
| Gatilho | Por quê |
|---|---|
| `PeriodicWorkRequest` a cada **1–3 h** (com flex) | mantém o banco atualizado |
| Ao abrir o app | dados frescos na tela |
| `BOOT_COMPLETED` (após o desbloqueio) | captura `DEVICE_SHUTDOWN/STARTUP` |
| `TIME_SET` / `TIMEZONE_CHANGED` | registra o `clock_event` e o fuso; ressincroniza a hora; reingere |
| Logo após a **meia-noite real** (+5 min), agendado por atraso calculado com `TrustedClock` | fecha o dia anterior (`status` final) |

Como os dados são retroativos, **atrasos de Doze/fabricante não causam perda** desde que
alguma ingestão ocorra em menos de 7 dias.

O agendamento do fechamento diário usa **atraso relativo** (`setInitialDelay` =
`meia-noite_real − agora_real`), nunca um horário do relógio do aparelho.

### 6.2 Algoritmo
1. Verificar a permissão: `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, uid, pkg)`.
   Se negada, gravar em `permission_log`, notificar o usuário e encerrar.
2. Verificar `UserManager.isUserUnlocked()`; se falso, fazer `retry`.
3. **Detectar mudança de relógio** comparando com a âncora da última execução
   (mesmo `BOOT_COUNT`): `delta = (wall_agora − wall_ant) − (elapsed_agora − elapsed_ant)`.
   Se `|delta| > 2 s`, abrir uma nova `clock_epoch`, ajustar a marca d'água pelo `delta` e
   registrar um `clock_event`.
4. **Obter o desvio vigente** pelo `TrustedClock` (Seção 3.4); sincronizar se necessário.
5. Janela de consulta: `[marca_d'água − 30 min de sobreposição, agora)`, no referencial do
   aparelho, dividida em blocos de 1 dia (consultas menores e retomáveis).
6. API 35+: `UsageEventsQuery.Builder(begin, end).setEventTypes(15,16,17,18,26,27)`.
   Abaixo disso: `queryEvents(begin, end)` filtrando na iteração.
7. Converter cada evento para `ts_utc_ms` e inserir com `INSERT OR IGNORE`, preservando a
   **ordem de iteração** (`seq_in_batch`) para desempate de timestamps iguais.
8. Avançar a marca d'água e gravar o `ingest_run`.
9. Re-sessionizar a partir da **última sessão fechada antes da janela** e recalcular os dias
   afetados (incremental e idempotente).

### 6.3 Deduplicação com mudança de relógio
Como o sistema desloca o histórico quando o relógio muda, um mesmo evento pode reaparecer
com outro timestamp de aparelho. Mitigação: (a) nunca reconsultar além da sobreposição
curta; (b) aplicar o `delta` detectado à marca d'água; (c) chave única inclui a `epoch_id`;
(d) após converter para hora real, descartar eventos do mesmo tipo/pacote a menos de 1 s de
um já existente; (e) marcar os dias envolvidos com a flag `TIME_CHANGED`, para
transparência.

---

## 7. Sessionização (máquina de estados)

Opera sobre `ts_utc_ms` (hora real). Estado: `interactive: Boolean?`,
`keyguardShown: Boolean?`, `openSince: Long?`.

```kotlin
fun unlocked(s) = s.interactive == true && s.keyguardShown == false

for (e in eventsOrdenadosPor(tsUtc, seq)) {
    val antes = unlocked(state)
    when (e.type) {
        SCREEN_INTERACTIVE     -> state.interactive = true
        SCREEN_NON_INTERACTIVE -> state.interactive = false
        KEYGUARD_SHOWN         -> state.keyguardShown = true
        KEYGUARD_HIDDEN        -> state.keyguardShown = false
        DEVICE_SHUTDOWN        -> { fecharSessao(e.tsUtc, SHUTDOWN_ESTIMATED); state = DESCONHECIDO }
        DEVICE_STARTUP         -> { state = DESCONHECIDO_POS_BOOT }
    }
    val depois = unlocked(state)
    if (!antes && depois) abrirSessao(e.tsUtc)
    if (antes && !depois) fecharSessao(e.tsUtc, motivo(e))
}
// Sessão ainda aberta: NÃO é persistida como fechada; a UI usa TrustedClock.now()
// se PowerManager.isInteractive() && !KeyguardManager.isKeyguardLocked().
```

Regras de borda:
- **Estado inicial desconhecido** (início do histórico ou pós-boot): não abrir sessão até
  existirem informações suficientes. Pós-boot: `keyguardShown = true` se o aparelho tem
  bloqueio (`KeyguardManager.isDeviceSecure()` ou houve `KEYGUARD_*` no histórico).
- **Aparelho sem bloqueio de tela**: o sistema pode nunca emitir `KEYGUARD_*`. Se não houver
  nenhum evento de keyguard no histórico observado e o aparelho não tiver bloqueio, tratar
  `keyguardShown = false`, o que faz o TTD ser igual ao tempo interativo.
- **Eventos repetidos** (ex.: dois `SCREEN_INTERACTIVE` seguidos): as transições são
  idempotentes.
- **Desligamento**: fechar no timestamp do `DEVICE_SHUTDOWN`. Se o desligamento foi
  abrupto (não houve `SCREEN_NON_INTERACTIVE`/`KEYGUARD_SHOWN` antes), usar a flag
  `ESTIMATED_END`, o que significa perda possível de até 20 min.
- **Sessões muito longas** (> 3 h, ex.: "manter tela ligada" durante o carregamento): são
  contadas, mas recebem a flag `LONG` e aparecem destacadas na tela de Qualidade.
- **Duração negativa / timestamps fora de ordem** (após ajuste de relógio): descartar o
  trecho e marcar `OUT_OF_ORDER`.
- **Toques fantasmas de 1–2 s** (tela acende e apaga) já ficam de fora, porque sem
  desbloqueio não há sessão. Sessões desbloqueadas curtas são mantidas e contam como
  desbloqueio.
- A sessão herda a **pior `time_quality`** dos eventos que a abrem e fecham.

---

## 8. Divisão por data (o "dia")

1. Base: **hora real** (`ts_utc_ms`, Seção 3). A data nunca vem do relógio do aparelho.
2. Cada sessão é **cortada nas meias-noites locais** que atravessa: uma sessão das 23:50 às
   00:20 dá 10 min a um dia e 20 min ao seguinte.
3. **Fuso de referência** (decisão D8):
   - Padrão: **fuso fixo** definido na instalação (ex.: `America/Sao_Paulo`), editável nos
     Ajustes. Mudar o fuso do aparelho não altera em que dia o uso cai.
   - Opção "seguir o fuso do aparelho" (para quem viaja): usa o `zone_history`. Um dia pode
     ter duração diferente e recebe uma flag.
   - As regras de horário de verão vêm do banco tz do sistema (`java.time`), atualizado pelo
     próprio Android. Dias de 23 h/25 h são suportados (o Brasil não tem horário de verão
     desde 2019, mas o código deve tratar o caso).
4. **Desbloqueios** são contados no dia em que a sessão **começou**.
5. **Corte do dia configurável** (padrão 00:00; opcional, ex.: 04:00, para quem dorme
   tarde — decisão D2).
6. `hourly_bucket` é preenchido com a mesma lógica de corte, por hora.
7. Sessões com **erro de hora** que possa mudar o dia (|erro| maior que a distância até a
   meia-noite) recebem a flag `BOUNDARY_UNCERTAIN`; raro com fontes 1–3.

---

## 9. Cobertura e qualidade de cada dia (base para médias corretas)

Diferenciar **"usou 0 min"** de **"não sabemos"** é o ponto que mais afeta a precisão das
médias.

- **Tempo observado** = união dos intervalos de consulta dos `ingest_run` bem-sucedidos
  (convertidos para hora real), com início efetivo em `max(query_begin, run_time − 7 dias)`,
  menos os períodos com a permissão revogada.
- Status do dia:
  - **COMPLETE**: o dia inteiro está dentro do tempo observado.
  - **PARTIAL**: dia da instalação/primeiro evento, dia de hoje, permissão revogada em parte
    do dia, ou perda após desligamento abrupto (> 0 min estimados).
  - **GAP**: dados expiraram antes de serem copiados (ficou sem ingestão por mais de 7–10 dias).
  - **NO_DATA**: nenhum dado.
- **Qualidade da hora do dia** = pior `time_quality` entre suas sessões. Um dia com hora
  `NAO_VERIFICADA` entra nas médias (as durações estão certas), mas aparece sinalizado até a
  correção retroativa.
- Telefone **desligado o dia todo** (`DEVICE_SHUTDOWN` sem `STARTUP` no dia) é **COMPLETE
  com 0 min**: o estado é conhecido.
- **Primeiro dia completo** = o dia seguinte ao do evento mais antigo disponível (o
  backfill na instalação traz cerca de 10 dias, sendo o primeiro deles parcial).
- **Backup/exportação**: exportar CSV/JSON via SAF (Storage Access Framework) e manter o
  Auto Backup do Android ativo para o banco, porque o histórico antigo só existe ali.

---

## 10. Estatísticas e médias

Regras gerais:
- **Por padrão só dias COMPLETE entram nas médias** (decisão D3). Sempre mostrar o
  **n = número de dias** usados.
- Calcular sempre a partir dos **totais diários**, nunca como média de médias. A média do
  mês é a soma dos minutos dividida pelo número de dias completos, não a média das médias
  semanais.
- Guardar em ms e arredondar apenas na exibição (`h:mm`).
- "Hoje", "esta semana" e "este mês" são definidos pela **data real** (`TrustedClock`).

| Estatística | Fórmula / uso |
|---|---|
| **Média diária** | `Σ TTD_d / n` |
| **Mediana diária** | robusta a dias atípicos (viagem, doença) |
| **Desvio-padrão** e **IQR (P25–P75)** | mostra a regularidade do uso |
| **Mín / máx** (com data) | dias extremos |
| **Média móvel de 7 dias** | gráfico de tendência sem ruído de dia da semana |
| **Dias úteis × fim de semana** | duas médias separadas, cada uma com o próprio n |
| **Média por dia da semana** | seg…dom (requer ≥ 2 semanas para fazer sentido) |
| **Variação semana atual × anterior** | `(média7_atual − média7_ant) / média7_ant` |
| **Tendência 30 dias** | inclinação da regressão linear (min/dia por semana) |
| **Intervalo de confiança 95 %** | `média ± t(n−1) · s/√n`; exibido quando n ≥ 5 |
| **Desbloqueios/dia, sessão média e mediana** | a partir de `session` |
| **Curva acumulada típica por hora** | "até esta hora você costuma ter X min" — compara *hoje até agora* sem projeções enganosas |

Períodos disponíveis: últimos 7 dias, últimos 30 dias, semana/mês do calendário, todo o
histórico e intervalo personalizado.

**Hoje (parcial)** aparece separado: "hoje até agora", incluindo a sessão em andamento,
comparado à curva acumulada típica — nunca misturado nas médias.

---

## 11. Validação da precisão

### 11.1 Testes unitários do `:core` (JVM, sem dispositivo)
Sequências sintéticas com resultado esperado exato (*golden tests*), com relógio do
aparelho e relógio real **simulados separadamente**:
1. Desbloqueio simples → bloqueio.
2. Tela acende por notificação, sem desbloquear → 0.
3. Bloqueio com atraso: `NON_INTERACTIVE` antes de `KEYGUARD_SHOWN`.
4. Aparelho sem bloqueio (sem eventos de keyguard).
5. Smart Lock / desbloqueio facial (keyguard oculto logo ao ligar).
6. Câmera pela tela de bloqueio (keyguard continua mostrado).
7. Sessão cruzando a meia-noite; sessão cruzando duas meias-noites.
8. Horário de verão (ex.: `America/New_York` e `America/Sao_Paulo` em 2018): dias de 23 h e 25 h.
9. Viagem com troca de fuso no meio de uma sessão (modo "seguir o aparelho") e o mesmo caso
   com fuso fixo.
10. Ajuste manual de relógio (+1 h / −1 h / +1 dia) com deslocamento do histórico do sistema
    → o uso continua no dia real.
11. **Relógio do aparelho 5 min atrasado** perto da meia-noite → sessão contada no dia real
    (exemplo 3.7).
12. **Sem internet desde o boot** → eventos `NAO_VERIFICADA`; ao sincronizar, reconversão
    retroativa somente da mesma época.
13. Fontes de hora divergentes (TrustedTime × SNTP > 2 s) → escolhe a de menor erro e
    registra a divergência.
14. Cálculo SNTP: desvio/RTT com respostas sintéticas; rejeição de `stratum 0`, KoD e `leap = 3`.
15. Reboot normal e desligamento abrupto (`DEVICE_SHUTDOWN` sintético).
16. Eventos duplicados, fora de ordem e com timestamps iguais.
17. Lacuna de retenção (ingestão após 12 dias) → dias `GAP`.
18. Permissão revogada e concedida novamente.
19. Estatísticas: média, mediana, IC e exclusão de dias parciais conferidas contra
    valores calculados à mão.

### 11.2 Validação em aparelho real (somente quando você autorizar)
- Protocolo de cronômetro: 10 sessões roteirizadas de durações conhecidas. Meta de
  **erro ≤ 1 s por sessão e ≤ 1 min por dia**.
- Teste de relógio: desligar a "data e hora automáticas", atrasar/adiantar o relógio e
  conferir que datas e horas exibidas continuam iguais às da internet (erro ≤ 1 s).
- Comparar os eventos lidos com `adb shell dumpsys usagestats`.
- Comparar com o Bem-estar Digital e documentar a diferença esperada (foreground × dispositivo).
- Matriz de aparelhos: Pixel (AOSP), Samsung (One UI), Xiaomi (HyperOS), Motorola e um sem
  Play services — confirmar os eventos 15–18 e 26–27 e as fontes de hora.

---

## 12. Permissões, privacidade e Play Store

- Manifest: `PACKAGE_USAGE_STATS` (com `tools:ignore="ProtectedPermissions"`),
  `RECEIVE_BOOT_COMPLETED`, **`INTERNET`** e **`ACCESS_NETWORK_STATE`**.
- **A internet é usada só para obter a hora** (TrustedTime, NTP.br/`time.google.com` e
  cabeçalho `Date`). **Nenhum dado de uso sai do aparelho.** Na seção "Segurança dos dados"
  da Play Store: nenhum dado coletado ou compartilhado.
- **Onboarding com divulgação clara** (o que é coletado, por quê, que fica só no aparelho e
  que a internet serve apenas para acertar a hora) **antes** de abrir
  `Settings.ACTION_USAGE_ACCESS_SETTINGS` (tentar abrir direto na página do app com
  `package:`; se o fabricante não suportar, abrir a lista).
- Verificar a permissão a cada abertura/ingestão. Se for revogada: aviso persistente e
  registro da lacuna.
- Não pedir isenção de otimização de bateria: não é necessária, porque os dados são
  retroativos. Mostrar alerta se a última ingestão tiver mais de 5 dias.
- Política de privacidade simples, exigida pela Play Store para apps com acesso a dados de uso.

---

## 13. Interface (resumo)

1. **Hoje**: TTD até agora (atualizado ao vivo), desbloqueios, sessão atual, comparação
   com a curva típica, **data/hora oficial da internet** e aviso se o relógio do aparelho
   estiver errado.
2. **Histórico**: calendário/lista por data com o status do dia (completo/parcial/lacuna)
   e gráfico de barras dos últimos 30 dias com a média móvel.
3. **Médias**: média, mediana, desvio, IC, dias úteis × fim de semana, por dia da semana
   e tendência — sempre com o n de dias.
4. **Qualidade dos dados**: cobertura, sessões longas, reboots abruptos, mudanças de
   relógio/fuso, **última sincronização da hora** (fonte, erro estimado, desvio do aparelho).
5. **Ajustes**: corte do dia, fuso de referência, inclusão de dias parciais e exportação.
6. (Depois) Widget, metas diárias e detalhamento por app.

---

## 14. Fases de implementação

| Fase | Entrega | Critério de pronto |
|---|---|---|
| **0 — Spike** | tela de debug que lista os eventos brutos dos últimos dias e a hora de cada fonte da internet | eventos 15–18/26–27 e ao menos uma fonte de hora confirmados no aparelho-alvo |
| **1 — Núcleo** | módulo `:core`: TrustedClock, SntpMath, Sessionizer, DaySplitter, Coverage e StatsEngine | todos os testes da Seção 11.1 passando |
| **2 — Dados** | Room, UsageEventSource, fontes de hora, workers, receivers | ingestão idempotente; backfill de ~10 dias convertido para hora real |
| **3 — UI** | onboarding, Hoje, Histórico, Médias e Qualidade | fluxo completo sem crash; permissão tratada |
| **4 — Validação** | protocolo de cronômetro, teste de relógio alterado e comparação com dumpsys | erro dentro da meta |
| **5 — Extras** | widget, exportação, metas, detalhamento por app | — |

---

## 15. Riscos e mitigações

| Risco | Mitigação |
|---|---|
| Eventos expiram (≈10 dias) | ingestão periódica + no boot + ao abrir; margem de 7 dias; status `GAP` explícito |
| Fabricante encerra o app/WorkManager | dados retroativos; recuperação ao abrir; alerta de última ingestão |
| Queda abrupta perde até 20 min | flag `ESTIMATED_END`; dia marcado como parcial |
| Relógio do aparelho errado ou alterado | hora real pela internet (Seção 3); conversão de todos os eventos; aviso na UI |
| Sem internet por muito tempo | último desvio da mesma época; marca `NAO_VERIFICADA`; correção retroativa ao reconectar |
| Sem Play services (TrustedTime indisponível) | `currentNetworkTimeClock()` (API 33+), SNTP NTP.br e cabeçalho HTTPS `Date` |
| Rede bloqueia UDP 123 (NTP) | fallback para TrustedTime e HTTPS `Date` |
| Servidor de hora falso/manipulado (NTP não autenticado) | verificação cruzada entre fontes; preferência por TrustedTime e HTTPS; descarta divergências > 2 s |
| Mudança de relógio desloca o histórico do sistema | âncora `elapsedRealtime`/`BOOT_COUNT`, épocas de relógio, ajuste da marca d'água, flag `TIME_CHANGED` |
| Fabricante com eventos de keyguard diferentes | Fase 0 em vários aparelhos; fallback "sem keyguard" |
| Permissão revogada | checagem a cada execução, `permission_log`, aviso na UI |
| Usuário troca de aparelho | histórico antigo só existe no nosso banco → exportação e Auto Backup |
| Interpretação diferente do Bem-estar Digital | definição explícita da métrica na própria UI (tela "Como medimos") |

---

## 16. Decisões em aberto (com padrão recomendado)

| # | Decisão | Padrão recomendado |
|---|---|---|
| D1 | Contar chamadas com o telefone no ouvido? | **Sim** (é uso do telefone); separar depois, se desejado |
| D2 | Início do dia | **00:00** (compatível com o Bem-estar Digital); configurável |
| D3 | Dias parciais nas médias? | **Não**; opção nos Ajustes |
| D4 | minSdk | **29** (Android 10) |
| D5 | Uso pela tela de bloqueio (câmera, chamada) | **Não conta** |
| D6 | Descontar a "cauda" até a tela apagar por inatividade | **Não** (é tempo de tela desbloqueada); pode virar métrica opcional de "tempo ocioso estimado" |
| D7 | Detalhamento por app já na 1ª versão? | **Não**; Fase 5 |
| D8 | Fuso para definir o dia | **Fuso fixo de referência** (definido na instalação, ex.: `America/Sao_Paulo`); opção "seguir o fuso do aparelho" para viagens |
| D9 | Fonte de hora principal | **TrustedTime** (Google), com fallback para `currentNetworkTimeClock()`, **NTP.br** e HTTPS `Date` |

---

## 17. Fontes consultadas

- Android Developers — [`UsageEvents.Event`](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
  (constantes, níveis de API e semântica de `DEVICE_SHUTDOWN/STARTUP`).
- Android Developers — [`UsageStatsManager`](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
  (retenção de "alguns dias", permissão, `queryEventsForSelf`, retorno vazio antes do desbloqueio).
- Android Developers — [`UsageEventsQuery.Builder`](https://developer.android.com/reference/android/app/usage/UsageEventsQuery.Builder) (API 35).
- Android Developers — [Exceções de broadcasts implícitos](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
- Android Developers — [`SystemClock`](https://developer.android.com/reference/android/os/SystemClock)
  (`currentNetworkTimeClock()` API 33, `elapsedRealtime`, relógios do sistema).
- Android Developers Blog — [TrustedTime API: introducing a reliable approach to time keeping for apps](https://android-developers.googleblog.com/2025/02/trustedtime-api-introducing-reliable-approach-to-time-keeping-for-apps.html).
- Google Play services — [`TrustedTimeClient`](https://developers.google.com/android/reference/com/google/android/gms/time/TrustedTimeClient),
  [`TimeSignal`](https://developers.google.com/android/reference/com/google/android/gms/time/trustedtime/TimeSignal),
  [`ComputedInstant`](https://developers.google.com/android/reference/com/google/android/gms/time/trustedtime/ComputedInstant)
  e [Play Services Time API](https://developers.google.com/location-context/time).
- NTP.br / NIC.br — [Apresentação NTP.br, IX Fórum Fortaleza 2026](https://fortaleza.forum.ix.br/files/apresentacao/arquivo/2460/02-ApresentacaoNTP.br-IXForumFortaleza2026.pdf)
  (servidores stratum 1 `a–e.st1.ntp.br`, Hora Legal Brasileira, NTS) e
  [NTP Pool — NIC.br](https://www.ntppool.org/a/ntpbr).
- AOSP `frameworks/base` (branch `main`):
  `services/usage/.../UsageStatsService.java` (flush a cada 20 min, correção de relógio,
  conversão `elapsedRealtime` → relógio do aparelho),
  `UserUsageStatsService.java` (`DEVICE_SHUTDOWN` sintético, rollover diário),
  `UsageStatsDatabase.java` (retenção de 10 dias, `onTimeChanged`),
  `IntervalStats.java` (trackers de `EventStats`),
  `services/core/.../am/ActivityManagerService.java` (`reportGlobalUsageEvent`),
  `services/core/.../wm/ActivityTaskManagerService.java` (`setLockScreenShown`).
- Pesquisa acadêmica sobre sessões de uso:
  [How to Measure Sessions of Mobile Device Use? (arXiv 1711.09408)](https://arxiv.org/pdf/1711.09408);
  [Hintze et al., UbiComp 2014](https://ins.jku.at/publications/2014/Hintze_2014_UbiComp.pdf)
  (uso com e sem desbloqueio); [User interaction analysis (Cardiff)](https://orca.cardiff.ac.uk/142395/1/User_interaction_analysis_ARDUOUS_Authors.pdf)
  (ciclos curtos de tela sem interação).

---

## 18. Blindagem (revisão de brechas, bugs e erros)

Revisão feita sobre o código implementado, com a correção aplicada para cada ponto.

| # | Brecha / bug | Efeito antes | Correção |
|---|---|---|---|
| B1 | Sessão aberta estendida até "agora" em recálculos sem nova leitura (ex.: após sincronizar a hora) | Somava tempo com a tela já apagada (até horas) | Sessão aberta vai só até o fim da última leitura (`knownUntil`) |
| B2 | Contador ao vivo extrapolava a partir de estado antigo | Pico falso ao voltar ao app | Extrapolação só se o cálculo tem menos de 10 min |
| B3 | Escolha da hora por "menor erro" | Um NTP falsificado/quebrado (rede hostil, VPN) definia a hora | Consenso: fonte autenticada (TrustedTime/HTTPS) é a referência; sem ela, vale a maioria; respostas implausíveis descartadas; fonte única não autenticada não é "verificada" |
| B4 | Sem internet, a hora do app seguia o relógio do aparelho | Mudar o relógio offline deslocava/duplicava eventos | Âncora estimada presa ao relógio monotônico, criada no boot |
| B5 | Correções pequenas de NTP deslocam o histórico do Android sem abrir "época" nova | Eventos da sobreposição duplicados | Deduplicação também pela hora real (±1,5 s) em qualquer época |
| B6 | "Sem tela de bloqueio" decidido para o histórico inteiro | Trocar de "Nenhum" para PIN zerava dias antigos no recálculo | Decisão por trecho entre boots |
| B7 | Sincronização e ingestão podiam se intercalar | Eventos ficavam sem correção retroativa | Um único mutex no pipeline + correção logo após a ingestão |
| B8 | NTP aceitava servidor com incerteza alta / carimbos fora de ordem | Hora com erro maior que o informado | Valida root delay/dispersion, ordem dos carimbos e RTT máximo; erro inclui a incerteza do servidor |
| B9 | Cabeçalho HTTP `Date` vindo de cache | Hora antiga | `Cache-Control: no-cache` e compensação do cabeçalho `Age` |
| B10 | Sessões antigas ficavam desatualizadas após recálculo parcial | Estatísticas de sessão com dados velhos | Recalcula também sessões que terminam na janela |
| B11 | Erros silenciosos nos totais | Número errado sem aviso | Checagens de consistência por dia (marca `INCONSISTENT`) e sessões sem sobreposição |
| B12 | App pausado pelo sistema (hibernação/bateria) por > 10 dias | Perda de dados sem aviso | Diagnóstico de "pausar app se não usado", otimização de bateria e maior intervalo sem coleta, com atalhos nos Ajustes |
| B13 | Aparelho que não registra eventos de tela | Tudo zerado sem explicação | Contagem de eventos por tipo (7 dias) e alerta |
| B14 | Receivers exportados | Superfície de ataque desnecessária | `exported=false` (broadcasts protegidos chegam do sistema) |
| B15 | Mudança de algoritmo não recalculava o histórico | Dias antigos com regra antiga | Versão do algoritmo salva; mudança força recálculo completo |

### Segunda revisão

| # | Bug | Efeito antes | Correção |
|---|---|---|---|
| B16 | Lacuna de dados (histórico expirou) no meio de uma sessão aberta | Um desbloqueio antes da lacuna contava como uso durante dias | Sessões são encerradas no início de cada lacuna real (≥ 30 min); estado volta a "desconhecido" |
| B17 | Pequenos desvios entre leituras após correções de hora | Dias marcados como parciais sem motivo | Cobertura une leituras com tolerância de 15 min |
| B18 | Android corrige o relógio no boot (RTC adiantado/atrasado) e desloca todo o histórico | Eventos da sobreposição voltavam deslocados e eram gravados de novo | Detecção do deslocamento exato comparando eventos já guardados com os relidos; registrado em "Relógio e fuso" |
| B19 | "Desbloqueios" contava também religar a tela dentro do atraso de bloqueio | Desbloqueios inflados | Só conta quem passou pela tela de bloqueio (ou aparelho sem bloqueio); sessões continuam contadas à parte |
| B20 | Curva "dia típico" com corte do dia ≠ 00:00 | Comparação de "hoje até agora" errada para quem usa corte às 3–4 h | Curva rotacionada para começar no horário de corte |
| B21 | Cancelamento de corrotina tratado como erro | Aviso falso "Erro ao atualizar" ao sair da tela; worker não parava ao ser cancelado | `CancellationException` é propagada em ingestão, pipeline, workers e tela |
| B22 | Fechamento diário se reagendava com REPLACE de dentro de si mesmo | Podia cancelar o próprio worker | Usa APPEND_OR_REPLACE quando se reagenda |
| B23 | Tentativas de sincronização a cada atualização quando só havia fonte não confirmada | Gasto de rede/bateria | Nova tentativa só após 15 min (exceto pedido manual) |
| B24 | Marca d'água e estado interno incluídos no backup | Restaurar em outro aparelho pulava o histórico local | Estado interno em `device_state`, fora do backup (com migração) |
| B25 | Aparelho sem BOOT_COUNT | Âncora de hora de um boot podia ser usada em outro | Usa o `boot_id` do kernel como alternativa |
| B26 | Permissão revogada durante a leitura | Período marcado como "observado" com zero uso | Revalida a permissão após ler; se revogada, a leitura falha e nada avança |
| B27 | Dias inconsistentes entravam nas médias | Média contaminada | Dias marcados como inconsistentes ficam fora das médias e da curva típica |

Limitações conhecidas (não resolvíveis só com o app): aparelho com root pode adulterar
qualquer dado; "forçar parada" ou limpar dados interrompe a coleta; trocar de usuário do
Android (tablets) não gera evento de tela para o usuário anterior.
