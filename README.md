# métricas — Tempo de Tela

App Android que mede com precisão o **tempo de tela desbloqueada** por dia, guarda o
histórico por data e calcula médias confiáveis. Toda data e hora é obtida **pela internet**
(Google TrustedTime, hora de rede do Android, NTP.br e cabeçalho HTTPS `Date`), não pelo
relógio do aparelho.

- Plano completo (pesquisa, definição da métrica, hora pela internet, arquitetura,
  algoritmo, médias, validação): [`docs/PLANO_TEMPO_DE_TELA.md`](docs/PLANO_TEMPO_DE_TELA.md)

## Estrutura

| Módulo | Conteúdo |
|---|---|
| `core/` | Kotlin puro (testável na JVM): sessionização, divisão por dia/fuso/DST, cobertura, estatísticas, matemática de hora (âncora monotônica, SNTP, HTTP Date), deduplicação. |
| `app/` | Android (Compose, Room, WorkManager): leitura do `UsageStatsManager`, fontes de hora pela internet, ingestão, recálculo, telas Hoje/Histórico/Médias/Qualidade/Ajustes. |

## Compilação

O GitHub Actions (`.github/workflows/android.yml`) roda os testes do `:core` e gera os APKs
(debug e release assinado com a chave de debug) a cada push. Os APKs ficam em
**Actions → execução → Artifacts → `tempo-de-tela-apk`**.

Localmente: `./gradlew :core:test :app:assembleDebug` (JDK 17 + Android SDK).

## Permissões

- **Acesso ao uso** (`PACKAGE_USAGE_STATS`): concedido pelo usuário nas Configurações.
- **Internet**: usada apenas para obter a hora oficial. Nenhum dado de uso sai do aparelho.
