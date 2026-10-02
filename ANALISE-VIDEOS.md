# Inspeção dos arquivos recebidos
Recebidos oito dos nove vídeos mencionados. Examinados por amostragem visual e telas em resolução original 2448 × 1080. Não são resultados obtidos de TwelveLabs, OCR.space ou Groq; nenhuma chave foi fornecida.

| Arquivo | Conteúdo | Evidências |
| --- | --- | --- |
| 16-09-15 | Elenco Tobol | 18 jogadores: 4 ATA, 6 MEI, 6 DEF, 2 GOL. Força geral 84; setores 85/85/84/86. Valor 163M, caixa 20,3M. |
| 16-09-46 | Calendário Tobol | Cards em seis colunas, rodada 19 com horário 22:18 sem data visível. Jogos fora precisam inverter placar mostrado. |
| 16-12-11 | Partida Levski × Ludogorets | Rival amar111_13 humano; meu time força 88, setores 86/86/90/89. Formação/força adversárias bloqueadas permanecem NI. Cadeado no relatório indica treino secreto ativo, conforme regra confirmada pelo usuário. |
| 16-12-37 | Elenco Levski | 17 jogadores: 3 ATA, 6 MEI, 6 DEF, 2 GOL. Falta um ATA para a meta. Valor 211M, caixa 25M. |
| 16-13-06 | Calendário Levski | Rodadas até 30 e copa. Rodada 15 mostra 20:39 sem data. Resumo da época não é jogo. |

Tobol: Barnes, Zirkzee, Olunga, Rollheiser; Foden, Reijnders, Scott, Jashari, Ounahi, Kondogbia; Koundé, Quansah, Williams, Simakan, Kehrer, Doukouré; R. Silva, Busurmanov.
Levski: Diaz, Saka, Kudus; Kimmich, B. Fernandes, Sano, Pavlovic, Éderson, Pašalić; Cucurella, Hincapié, Khusanov, van de Ven, Pavard, Udol; Petrovic, Pau López.

Camisa laranja é treino. Cartão amarelo à direita não é venda/treino. Setas vermelha/verde perto da camisa são venda. A força de Foden é Med 90, não Ata 92. Goleiros usam força na coluna Def. A camisa amarela do árbitro não determina severidade: ler termômetro.

Teste local Tesseract na tela Tobol aos 12 segundos reconheceu oito nomes, idades e valores, mas perdeu as posições e a vírgula de um valor (Williams 7,3M virou 73M). Por isso revisão, fusão e avisos de divergência são obrigatórios. O parser mantém posição/força NI quando não há evidência.
Fixture em tests/fixtures/tobol-native-overlay.json; dados desses vídeos não foram inseridos automaticamente nos slots.


## Três arquivos adicionais
- 16-14-00 (32,1s): partida FK Buxoro × Nasaf; meu time Nasaf, força 70, rival 85. Relatório aberto do FK Buxoro: 4-4-2 B, Jogar pelas alas, À zona, impedimento Não, desarme Agressivo, campo de treinamento Não (texto "não foram em Estágio"), estádio nível 1. Treino secreto não deve ser marcado Não só porque não há cadeado nesta tela.
- 16-14-28 (14,1s): elenco Nasaf, força 70, setores GOL 80 / DEF 69 / MEI 68 / ATA 72, valor 39,6M, caixa 4,7M. 17 jogadores: 3 ATA, 6 MEI, 6 DEF, 2 GOL. Camisas laranja visíveis em B. Abdikholikov, Alisson e Nsoki; Perri tem camisa laranja com mangas verdes, também treino. Cartões amarelos em Mukhitdinov/Eshmurodov não são treino/venda.
- 16-14-57 (17s): calendário Nasaf, rodadas até 34, partidas de copa e resumo final. Rodada 4 contra FK Buxoro mostra 20:32 sem data: date NI. Jornada 2 fora mostra 2-0/D: placar do Nasaf 0x2. Fases futuras sem rival comprovado continuam opponent NI.

Correção de regra: cadeado **no relatório do rival** = treino secreto Sim. Ícones genéricos de menu ou transições não confirmam essa condição. Os demais campos ocultos continuam NI; dados visíveis em outras telas podem ser aproveitados.
