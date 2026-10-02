# OSM AI Coach Pro

PWA mobile-first para uso pessoal no OSM 26 Android. Base reconstruída sem arquivos de hotfix/versionamento por nome.

## O que já está implementado

- 4 slots independentes (S1–S4), persistidos em `localStorage`.
- PWA instalável no Android, com manifest, ícones e service worker.
- Upload de vídeos e múltiplas imagens direto do Android.
- Extração de frames no navegador + deduplicação perceptual (dHash).
- Pré-processamento de imagem e OCR local via Tesseract.js.
- Fallback opcional para OCR.Space por `/api/ocr` sem expor chave.
- AI Router serverless com OpenRouter → Groq → Gemini, timeout, retry curto e fallback.
- Parsers determinísticos para árbitro, formação, plano, marcação, impedimento, treino secreto e força.
- Preservação de dado antigo válido quando leitura nova retorna `NI`.
- Leitura/deduplicação básica de elenco e reconstrução incremental entre análises.
- Calendário incremental, sem transformar card vazio em vitória.
- Motor determinístico de tática com formações alternativas e regra da tática forte 4-3-3 quando vantagem >= 13.
- Desarme influenciado pela cor do árbitro (verde, azul, amarelo, laranja, vermelho).
- Diretor com meta 4 ATA / 6 MEI / 6 DEF / 2 GOL e máximo de 4 vendas.
- Aprendizado por resultado, com pesos por formação e contexto preservado.
- Notificações locais da PWA em 20 e 10 minutos quando o calendário tem data/hora e o app consegue agendar no dispositivo.
- Modo offline para shell do app e continuidade com dados locais.

## Publicação na Vercel

O repositório pode ser conectado diretamente à Vercel. Configuração já está em `vercel.json`.

### Environment Variables

No projeto Vercel: **Settings → Environment Variables**. Use os nomes abaixo; todos são opcionais, e a ausência de um provedor apenas o remove do fallback:

- `OPENROUTER_API_KEY`
- `GROQ_API_KEY`
- `GEMINI_API_KEY`
- `OCR_SPACE_API_KEY`

Nunca coloque essas chaves em `app.js`, `src/*`, HTML ou `localStorage`.

## Android

Após publicar em HTTPS pela Vercel, abra o endereço no Chrome do Android e use **Instalar app / Adicionar à tela inicial**. O app abre em modo standalone como aplicativo.

## Desenvolvimento local

```bash
npm install
npm run dev
```

Teste e build:

```bash
npm run check
```

## Limite importante

OCR de interfaces visuais é dependente da resolução, tema e telas reais do OSM. A arquitetura evita inventar dados e mantém `NI`, mas a precisão final deve ser calibrada com vídeos/imagens reais do jogo. O pipeline foi feito para essa calibração sem recriar a arquitetura.
