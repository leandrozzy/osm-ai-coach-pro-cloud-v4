# Publicar no projeto existente

1. Extraia o ZIP. Os arquivos index.html, package.json e vercel.json devem ficar na raiz do repositório osm-ai-coach-pro-cloud-v4, junto com as pastas src, api, assets, scripts e tests.
2. Substitua os arquivos da tentativa anterior e faça commit na main. Não é necessário apagar o histórico nem excluir o projeto.
3. A integração GitHub–Vercel existente deve iniciar uma publicação automaticamente. A nova versão só está publicada após o deploy ficar Ready.
4. Abra o endereço do projeto. Em Configurações, informe sua chave Gemini se desejar leitura visual/IA. Sem chave, edição e tática local continuam disponíveis.
5. No Chrome Android, menu ⋮ → Instalar app/Adicionar à tela inicial.

As configurações de build estão em vercel.json. Se o projeto tiver overrides antigos na Vercel, use Framework Other, Build Command npm run build, Output Directory dist e Root Directory vazio.

O aplicativo guarda dados no dispositivo. Faça backup pela aba Configurações antes de limpar os dados do navegador.

Limites: IA depende de chave/cota; leitura de mídias reais ainda requer calibração; notificações atuais dependem do app aberto. Nenhuma tática garante vitória.
