# OSM AI Coach — Android Collector v1

Primeira camada nativa para transformar o OSM AI Coach Pro em um app que observa o OSM enquanto o usuário navega normalmente.

## O que já faz

- Abre o OSM pelo pacote oficial `com.gamebasics.osm`.
- Usa `AccessibilityService.takeScreenshot()` (Android 11+) apenas quando eventos pertencem ao OSM.
- Não executa cliques, gestos, escalação, compras ou ações dentro do jogo.
- Cria uma sessão automática quando o OSM aparece.
- Captura telas após mudanças de janela/conteúdo, com debounce.
- Calcula hash perceptual e elimina capturas praticamente repetidas.
- Encerra a sessão automaticamente quando o usuário sai do OSM.
- Salva `session.json` + frames JPEG em armazenamento privado do app.
- Expõe uma ponte JavaScript (`window.OsmCollector`) para o Coach web consumir a sessão sem upload intermediário.
- Inclui workflow de GitHub Actions para gerar APK debug.

## Ponte JavaScript disponível

Dentro do WebView do Coach:

```js
OsmCollector.startOsmSession()
OsmCollector.collectorStatus()
OsmCollector.latestSession()
OsmCollector.latestFrameBase64(index)
```

A página recebe também o evento:

```js
window.addEventListener('osm-collector-change', () => { ... })
```

## Segurança da versão atual

Este módulo foi criado isoladamente. Ele não altera `index.html`, `app.js`, `src/`, `api/` nem o sistema de release do PWA atual.

## Próxima integração

1. Definir a URL real do OSM AI Coach em `app/build.gradle.kts` (`BuildConfig.COACH_URL`).
2. Adicionar no PWA um módulo `src/android-collector.js` que:
   - detecta `window.OsmCollector`;
   - lê `latestSession()`;
   - transforma cada frame base64 em `Blob/File`;
   - envia esses frames aos leitores atuais (`match-reader`, elenco, calendário, mercado);
   - grava o resultado nos mesmos stores usados hoje pelos 4 slots.
3. Exibir na aba Hoje cobertura por slot e o que faltou abrir.

## Permissão inicial no Android

Configurações > Acessibilidade > Apps instalados > **OSM AI Coach — leitura automática** > Permitir.

É uma ativação manual do Android. Depois disso, o uso normal é abrir o Coach e tocar em **Abrir OSM e começar**.
