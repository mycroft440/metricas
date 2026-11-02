# Plano — Métricas confiáveis de tempo de tela desbloqueada (Android)

> Objetivo: medir, com a maior precisão possível, **quanto tempo por dia o telefone fica
> desbloqueado e em uso**, guardar o histórico por data e calcular médias confiáveis
> (diária, semanal, mensal, dias úteis × fim de semana, tendência).

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
- **Primeiro desbloqueio** e **último bloqueio** do dia.
- **Distribuição por hora do dia** (mapa de calor 24 × 7).
- (Fase posterior) **Tempo por app** via eventos `ACTIVITY_RESUMED/PAUSED`, apenas como
  detalhamento — nunca como base do total.

### 1.3 Por que não copiar o número do Bem-estar Digital

O Bem-estar Digital (e equivalentes de fabricantes) soma o **tempo de apps em primeiro
plano**; a métrica aqui é **do dispositivo** (desbloqueado + tela interativa). Os números
vão diferir em alguns minutos por dia, e isso é esperado. A comparação com o Bem-estar
Digital será usada só como verificação de sanidade (Seção 9).

---

## 2. Pesquisa: fontes de dados disponíveis no Android

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
4. **Correção de relógio** — quando o relógio muda mais de 2 s
   (`TIME_CHANGE_THRESHOLD_MILLIS`), o sistema **desloca todo o histórico armazenado pela
   mesma diferença** (`UsageStatsDatabase.onTimeChanged()` renomeia os arquivos). Os
   timestamps de eventos já lidos por nós podem, portanto, voltar **diferentes** numa nova
   consulta. Isso afeta a deduplicação (Seção 5.3).
5. **Os buckets agregados do sistema não servem como fonte de dados diária**: o bucket
   diário começa no instante da última virada (`UnixCalendar`, sem fuso horário), e não à
   meia-noite local. Além disso, o `queryEventStats()` informa a duração de
   `KEYGUARD_HIDDEN` incluindo tempo de tela **apagada** (bloqueio com atraso). Portanto
   **não dá para obter a interseção "interativo E desbloqueado" pelos agregados**;
   calcularemos tudo a partir dos eventos brutos.
6. A partir do Android 11, `queryEvents` retorna vazio enquanto o usuário não desbloquear o
   aparelho após o boot (`isUserUnlocked() == false`).
7. No Android 15 (API 35) existe `queryEvents(UsageEventsQuery)` com
   `setEventTypes(...)`, que filtra no sistema e é bem mais eficiente. Nas versões
   anteriores, filtra-se durante a iteração.
8. Os eventos ainda não gravados em disco (em memória) **são retornados** pela consulta, ou
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

`BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`. Servem para disparar a ingestão e
registrar mudanças de relógio/fuso.

### 2.5 Descartados

- **AccessibilityService**: invasivo, a política da Play Store restringe o uso e é
  desnecessário.
- **BatteryStats / `dumpsys`**: exigem permissões de sistema. Só via `adb`, para
  validação.

---

## 3. Arquitetura

```
┌─────────────────────────── :app (Android) ────────────────────────────┐
│ UI (Compose): Hoje · Histórico · Médias · Qualidade dos dados · Ajustes│
│ Workers (WorkManager) · Receivers (BOOT/TIME_SET/TIMEZONE)             │
│ UsageEventSource (UsageStatsManager)  ·  Room (banco local)            │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ usa
┌──────────────────────────────▼────────────────────────────────────────┐
│ :core (Kotlin puro, sem Android → testável em JVM)                    │
│  Sessionizer (máquina de estados) · DaySplitter (fuso/DST)            │
│  CoverageCalculator · StatsEngine (médias, mediana, desvio, tendência)│
└───────────────────────────────────────────────────────────────────────┘
```

- **Linguagem:** Kotlin. **UI:** Jetpack Compose. **Persistência:** Room.
  **Agendamento:** WorkManager. **Datas:** `java.time` (nativo).
- **minSdk 29** (Android 10), recomendado: garante `DEVICE_STARTUP/SHUTDOWN`. Com minSdk 28,
  o reboot teria de ser inferido por heurística. **targetSdk:** o mais recente.
- **Separar o núcleo (`:core`)** permite testar todo o algoritmo com sequências sintéticas
  de eventos, sem emulador.

---

## 4. Modelo de dados (Room)

```text
raw_event        (id PK, ts_utc_ms, type, pkg, cls, seq_in_batch, ingest_run_id)
                 UNIQUE(ts_utc_ms, type, pkg, cls)
                 -- apenas tipos 15,16,17,18,26,27 (+1,2 quando houver o detalhamento por app)

ingest_run       (id PK, started_at_utc, query_begin_utc, query_end_utc,
                  events_read, events_new, ok, error,
                  elapsed_realtime_ms, boot_count, wall_clock_ms)   -- âncora de relógio

clock_event      (id PK, detected_at_utc, kind[TIME_SET|TIMEZONE_CHANGED|REBOOT],
                  delta_ms, old_zone, new_zone)

zone_history     (effective_from_utc PK, zone_id)   -- ex.: "America/Sao_Paulo"

permission_log   (ts_utc, granted BOOLEAN)          -- acesso ao uso concedido/revogado

session          (id PK, start_utc, end_utc, duration_ms,
                  start_reason[UNLOCK|SCREEN_ON_NO_KEYGUARD],
                  end_reason[SCREEN_OFF|KEYGUARD_SHOWN|SHUTDOWN|SHUTDOWN_ESTIMATED|OPEN],
                  flags BITMASK[LONG|TIME_CHANGED|ESTIMATED_END|OUT_OF_ORDER],
                  algo_version)

daily_summary    (local_date PK, zone_id, day_length_ms,          -- 23h/24h/25h (DST)
                  unlocked_ms, screen_on_ms, unlocks, sessions,
                  longest_session_ms, median_session_ms,
                  first_unlock_local, last_lock_local,
                  covered_ms, coverage_ratio,
                  status[COMPLETE|PARTIAL|NO_DATA|GAP],
                  quality_flags, algo_version, computed_at_utc)

hourly_bucket    (local_date, hour 0..23, unlocked_ms, unlocks)    -- mapa de calor
```

Princípios:
- **Todos os instantes são guardados em UTC (ms)**. Datas locais só existem nas tabelas
  agregadas, sempre com o `zone_id` usado.
- **`algo_version`**: ao mudar o algoritmo, os resumos são recalculados a partir dos
  `raw_event`, que são a fonte da verdade e são imutáveis.
- O histórico além de 10 dias existe **só no nosso banco**, por isso backup e exportação
  são importantes (Seção 8).

---

## 5. Ingestão (copiar eventos do sistema antes de expirarem)

### 5.1 Quando rodar
| Gatilho | Por quê |
|---|---|
| `PeriodicWorkRequest` a cada **1–3 h** (com flex) | mantém o banco atualizado |
| Ao abrir o app | dados frescos na tela |
| `BOOT_COMPLETED` (após o desbloqueio) | captura `DEVICE_SHUTDOWN/STARTUP` |
| `TIME_SET` / `TIMEZONE_CHANGED` | registra o `clock_event` e o fuso; reingere |
| Diário, logo após 00:05 local | fecha o dia anterior (`status` final) |

Como os dados são retroativos, **atrasos de Doze/fabricante não causam perda** desde que
alguma ingestão ocorra em menos de 7 dias.

### 5.2 Algoritmo
1. Verificar a permissão: `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, uid, pkg)`.
   Se negada, gravar em `permission_log`, notificar o usuário e encerrar.
2. Verificar `UserManager.isUserUnlocked()`; se falso, fazer `retry`.
3. **Detectar mudança de relógio** comparando com a âncora da última execução
   (mesmo `BOOT_COUNT`): `delta = (wall_agora − wall_ant) − (elapsed_agora − elapsed_ant)`.
   Se `|delta| > 2 s`, ajustar a marca d'água pelo `delta` e registrar um `clock_event`.
4. Janela de consulta: `[marca_d'água − 30 min de sobreposição, agora)`, dividida em
   blocos de 1 dia (consultas menores e retomáveis).
5. API 35+: `UsageEventsQuery.Builder(begin, end).setEventTypes(15,16,17,18,26,27)`.
   Abaixo disso: `queryEvents(begin, end)` filtrando na iteração.
6. Inserir com `INSERT OR IGNORE`, preservando a **ordem de iteração** (`seq_in_batch`)
   para desempate de timestamps iguais.
7. Avançar a marca d'água e gravar o `ingest_run`.
8. Re-sessionizar a partir da **última sessão fechada antes da janela** e recalcular os dias
   afetados (incremental e idempotente).

### 5.3 Deduplicação com mudança de relógio
Como o sistema desloca o histórico quando o relógio muda, um mesmo evento pode reaparecer
com outro timestamp. Mitigação: (a) nunca reconsultar além da sobreposição curta;
(b) aplicar o `delta` detectado à marca d'água; (c) marcar os dias envolvidos com a flag
`TIME_CHANGED`, para transparência.

---

## 6. Sessionização (máquina de estados)

Estado: `interactive: Boolean?`, `keyguardShown: Boolean?`, `openSince: Long?`.

```kotlin
fun unlocked(s) = s.interactive == true && s.keyguardShown == false

for (e in eventsOrdenadosPor(ts, seq)) {
    val antes = unlocked(state)
    when (e.type) {
        SCREEN_INTERACTIVE     -> state.interactive = true
        SCREEN_NON_INTERACTIVE -> state.interactive = false
        KEYGUARD_SHOWN         -> state.keyguardShown = true
        KEYGUARD_HIDDEN        -> state.keyguardShown = false
        DEVICE_SHUTDOWN        -> { fecharSessao(e.ts, SHUTDOWN_ESTIMATED); state = DESCONHECIDO }
        DEVICE_STARTUP         -> { state = DESCONHECIDO_POS_BOOT }
    }
    val depois = unlocked(state)
    if (!antes && depois) abrirSessao(e.ts)
    if (antes && !depois) fecharSessao(e.ts, motivo(e))
}
// Sessão ainda aberta: NÃO é persistida como fechada; a UI usa
// agora() se PowerManager.isInteractive() && !KeyguardManager.isKeyguardLocked().
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

---

## 7. Divisão por data (o "dia")

1. Cada sessão é **cortada nas meias-noites locais** que atravessa: uma sessão das 23:50 às
   00:20 dá 10 min a um dia e 20 min ao seguinte.
2. Fuso usado = o vigente no instante (`zone_history`), calculado com `java.time.ZonedDateTime`.
   Isso cobre o **horário de verão** (dias de 23 h/25 h; o Brasil não tem desde 2019, mas
   o código deve suportar) e **viagens** (um dia pode ter duração diferente; o dia recebe
   uma flag).
3. **Desbloqueios** são contados no dia em que a sessão **começou**.
4. **Corte do dia configurável** (padrão 00:00; opcional, ex.: 04:00, para quem dorme
   tarde — decisão D2).
5. `hourly_bucket` é preenchido com a mesma lógica de corte, por hora.

---

## 8. Cobertura e qualidade de cada dia (base para médias corretas)

Diferenciar **"usou 0 min"** de **"não sabemos"** é o ponto que mais afeta a precisão das
médias.

- **Tempo observado** = união dos intervalos `[query_begin_efetivo, query_end]` dos
  `ingest_run` bem-sucedidos, onde `query_begin_efetivo = max(query_begin, run_time − 7 dias)`,
  menos os períodos com a permissão revogada.
- Status do dia:
  - **COMPLETE**: o dia inteiro está dentro do tempo observado.
  - **PARTIAL**: dia da instalação/primeiro evento, dia de hoje, permissão revogada em parte
    do dia, ou perda após desligamento abrupto (> 0 min estimados).
  - **GAP**: dados expiraram antes de serem copiados (ficou sem ingestão por mais de 7–10 dias).
  - **NO_DATA**: nenhum dado.
- Telefone **desligado o dia todo** (`DEVICE_SHUTDOWN` sem `STARTUP` no dia) é **COMPLETE
  com 0 min**: o estado é conhecido.
- **Primeiro dia completo** = o dia seguinte ao do evento mais antigo disponível (o
  backfill na instalação traz cerca de 10 dias, sendo o primeiro deles parcial).
- **Backup/exportação**: exportar CSV/JSON via SAF (Storage Access Framework) e manter o
  Auto Backup do Android ativo para o banco, porque o histórico antigo só existe ali.

---

## 9. Estatísticas e médias

Regras gerais:
- **Por padrão só dias COMPLETE entram nas médias** (decisão D3). Sempre mostrar o
  **n = número de dias** usados.
- Calcular sempre a partir dos **totais diários**, nunca como média de médias. A média do
  mês é a soma dos minutos dividida pelo número de dias completos, não a média das médias
  semanais.
- Guardar em ms e arredondar apenas na exibição (`h:mm`).

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

## 10. Validação da precisão

### 10.1 Testes unitários do `:core` (JVM, sem dispositivo)
Sequências sintéticas com resultado esperado exato (*golden tests*):
1. Desbloqueio simples → bloqueio.
2. Tela acende por notificação, sem desbloquear → 0.
3. Bloqueio com atraso: `NON_INTERACTIVE` antes de `KEYGUARD_SHOWN`.
4. Aparelho sem bloqueio (sem eventos de keyguard).
5. Smart Lock / desbloqueio facial (keyguard oculto logo ao ligar).
6. Câmera pela tela de bloqueio (keyguard continua mostrado).
7. Sessão cruzando a meia-noite; sessão cruzando duas meias-noites.
8. Horário de verão (ex.: `America/New_York` e `America/Sao_Paulo` em 2018): dias de 23 h e 25 h.
9. Viagem com troca de fuso no meio de uma sessão.
10. Ajuste manual de relógio (+1 h / −1 h) com deslocamento do histórico.
11. Reboot normal e desligamento abrupto (`DEVICE_SHUTDOWN` sintético).
12. Eventos duplicados, fora de ordem e com timestamps iguais.
13. Lacuna de retenção (ingestão após 12 dias) → dias `GAP`.
14. Permissão revogada e concedida novamente.
15. Estatísticas: média, mediana, IC e exclusão de dias parciais conferidas contra
    valores calculados à mão.

### 10.2 Validação em aparelho real (somente quando você autorizar)
- Protocolo de cronômetro: 10 sessões roteirizadas de durações conhecidas. Meta de
  **erro ≤ 1 s por sessão e ≤ 1 min por dia**.
- Comparar os eventos lidos com `adb shell dumpsys usagestats`.
- Comparar com o Bem-estar Digital e documentar a diferença esperada (foreground × dispositivo).
- Matriz de aparelhos: Pixel (AOSP), Samsung (One UI), Xiaomi (HyperOS), Motorola —
  confirmar que os eventos 15–18 e 26–27 existem em cada um.

---

## 11. Permissões, privacidade e Play Store

- Manifest: `PACKAGE_USAGE_STATS` (com `tools:ignore="ProtectedPermissions"`),
  `RECEIVE_BOOT_COMPLETED`. **Sem permissão de INTERNET**: os dados nunca saem do aparelho.
- **Onboarding com divulgação clara** (o que é coletado, por quê, que fica só no aparelho)
  **antes** de abrir `Settings.ACTION_USAGE_ACCESS_SETTINGS` (tentar abrir direto na página
  do app com `package:`; se o fabricante não suportar, abrir a lista).
- Verificar a permissão a cada abertura/ingestão. Se for revogada: aviso persistente e
  registro da lacuna.
- Não pedir isenção de otimização de bateria: não é necessária, porque os dados são
  retroativos. Mostrar alerta se a última ingestão tiver mais de 5 dias.
- Política de privacidade simples, exigida pela Play Store para apps com acesso a dados de uso.

---

## 12. Interface (resumo)

1. **Hoje**: TTD até agora (atualizado ao vivo), desbloqueios, sessão atual e comparação
   com a curva típica.
2. **Histórico**: calendário/lista por data com o status do dia (completo/parcial/lacuna)
   e gráfico de barras dos últimos 30 dias com a média móvel.
3. **Médias**: média, mediana, desvio, IC, dias úteis × fim de semana, por dia da semana
   e tendência — sempre com o n de dias.
4. **Qualidade dos dados**: cobertura, sessões longas, reboots abruptos, mudanças de relógio/fuso.
5. **Ajustes**: corte do dia, inclusão de dias parciais e exportação.
6. (Depois) Widget, metas diárias e detalhamento por app.

---

## 13. Fases de implementação

| Fase | Entrega | Critério de pronto |
|---|---|---|
| **0 — Spike** | tela de debug que lista os eventos brutos dos últimos dias | eventos 15–18/26–27 confirmados no aparelho-alvo |
| **1 — Núcleo** | módulo `:core`: Sessionizer, DaySplitter, Coverage e StatsEngine | todos os testes da Seção 10.1 passando |
| **2 — Dados** | Room, UsageEventSource, workers, receivers e âncora de relógio | ingestão idempotente; backfill de ~10 dias |
| **3 — UI** | onboarding, Hoje, Histórico, Médias e Qualidade | fluxo completo sem crash; permissão tratada |
| **4 — Validação** | protocolo de cronômetro + comparação com dumpsys | erro dentro da meta |
| **5 — Extras** | widget, exportação, metas, detalhamento por app | — |

---

## 14. Riscos e mitigações

| Risco | Mitigação |
|---|---|
| Eventos expiram (≈10 dias) | ingestão periódica + no boot + ao abrir; margem de 7 dias; status `GAP` explícito |
| Fabricante encerra o app/WorkManager | dados retroativos; recuperação ao abrir; alerta de última ingestão |
| Queda abrupta perde até 20 min | flag `ESTIMATED_END`; dia marcado como parcial |
| Mudança de relógio desloca o histórico do sistema | âncora `elapsedRealtime`/`BOOT_COUNT`, ajuste da marca d'água, flag `TIME_CHANGED` |
| Fabricante com eventos de keyguard diferentes | Fase 0 em vários aparelhos; fallback "sem keyguard" |
| Permissão revogada | checagem a cada execução, `permission_log`, aviso na UI |
| Usuário troca de aparelho | histórico antigo só existe no nosso banco → exportação e Auto Backup |
| Interpretação diferente do Bem-estar Digital | definição explícita da métrica na própria UI (tela "Como medimos") |

---

## 15. Decisões em aberto (com padrão recomendado)

| # | Decisão | Padrão recomendado |
|---|---|---|
| D1 | Contar chamadas com o telefone no ouvido? | **Sim** (é uso do telefone); separar depois, se desejado |
| D2 | Início do dia | **00:00** (compatível com o Bem-estar Digital); configurável |
| D3 | Dias parciais nas médias? | **Não**; opção nos Ajustes |
| D4 | minSdk | **29** (Android 10) |
| D5 | Uso pela tela de bloqueio (câmera, chamada) | **Não conta** |
| D6 | Descontar a "cauda" até a tela apagar por inatividade | **Não** (é tempo de tela desbloqueada); pode virar métrica opcional de "tempo ocioso estimado" |
| D7 | Detalhamento por app já na 1ª versão? | **Não**; Fase 5 |

---

## 16. Fontes consultadas

- Android Developers — [`UsageEvents.Event`](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
  (constantes, níveis de API e semântica de `DEVICE_SHUTDOWN/STARTUP`).
- Android Developers — [`UsageStatsManager`](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
  (retenção de "alguns dias", permissão, `queryEventsForSelf`, retorno vazio antes do desbloqueio).
- Android Developers — [`UsageEventsQuery.Builder`](https://developer.android.com/reference/android/app/usage/UsageEventsQuery.Builder) (API 35).
- Android Developers — [Exceções de broadcasts implícitos](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
- AOSP `frameworks/base` (branch `main`):
  `services/usage/.../UsageStatsService.java` (flush a cada 20 min, correção de relógio),
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
