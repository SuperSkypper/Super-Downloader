# Super Downloader

App Android que recebe um link do X/Twitter (compartilhado ou colado), baixa o
video na qualidade escolhida e oferece o arquivo no share sheet.

Nome visivel no Android: **Downloader** (`app_name`). O `applicationId` continua
`app.skypper.superdownloader` — trocar isso instala um app novo, sem prefs
nem grants do SAF. A pasta de destino tambem continua `Download/Super Downloader`.

## Telas

Uma pagina so, no desenho do Super Capture (a referencia de UI do projeto):

- Uma pagina so. De cima para baixo: barra `Download Folder:` (abre o seletor
  de pasta do SAF), bloco de status, botao Share (so com download concluido),
  History (ocupa o resto da tela), Quality + engrenagem, barra do link, botao
  Download/Cancel no rodape.
- Nao existe aba Settings nem sistema de templates: a pasta e uma so e a
  engrenagem guarda a **qualidade de share** (ver abaixo).

Tudo em ingles na interface, inclusive hints.

## Arquivos

- `MainActivity.kt` — as duas abas, a tab bar (overlay com hairline, indicador
  `accentDim` contornado), o download, o share e o History.
- `Palette.kt` — a mesma classe do Super Capture: resolve `colors.xml` e oferece
  `alpha()`/`roundRect()`; nada de constante `Int` em companion.
- `Models.kt` — `Quality`/`QualityPreset` (WhatsApp, Highest Quality, Custom +
  escada 4K…144p) e `MediaItem` com as variantes do post.
- `Resolver.kt` — link -> lista de MP4 com as variantes (`master.m3u8` do tweet).
- `Downloader.kt` — grava no SAF escolhido ou em `Download/Super Downloader`.
- `Transcode.kt` — recomprime quando o preset WhatsApp passa de 62 MB.
- `Thumbs.kt` — primeiro frame do video, quadrado, para a linha do History.
- `Prefs.kt` — destino, preset, altura do Custom, toggle automatico e o History
  (10 itens: link + uri do arquivo + caminho da miniatura).

## Barra de status (estados)

Exatamente cinco, e nada mais escreve nela:

- `Waiting for Download`
- `Downloading`
- `Compressing`
- `Cancelling`
- `Download Complete`

A linha de detalhe, dentro do bloco e abaixo do titulo, mostra o que muda:
`78% · 19,9 MB of 25,4 MB · 5,5 MB left`, o limite na compressao, o resumo no
fim, ou a mensagem de erro em vermelho. Coisas que NAO sao estado (link colado,
qualidade escolhida, link copiado) nao tocam no status.

## Qualidade de share (a engrenagem)

O preset em QUALITY vale para o link colado no campo; a engrenagem
(`ShareQualityDialog`) define a qualidade usada quando o app e aberto pela folha
de compartilhamento do sistema. `prefs.qualityFor(fromShare)` decide, e
`fromShare` vem do `ACTION_SEND` do intent. Desligado = usa o preset da tela.
Ligar o toggle sem nada escolhido herda o preset da tela, para o estado nao ficar
ambiguo (nenhum chip marcado).

Verificado no aparelho, mesmo video, dois caminhos: `ACTION_SEND` com share
`Highest Quality` gerou `angieverses_..._2114529898609930240.mp4` (19,9 MB,
720x1280, sem recompressao) enquanto a tela principal estava em WhatsApp; o
mesmo link digitado no campo gerou `..._whatsapp.mp4`.
O painel usa a linguagem do app (fundo do tema, moldura do toggle, chips de
34dp) e **duas bordas**, nao tres: painel + linha do toggle.

## Design (o do Super Capture)

- Paleta em `res/values/colors.xml`, copia fiel: `bg #0B0D10`, `surface #14181F`,
  `surface_variant #1C222B`, `border #2A323D`, `text #E6EDF3`,
  `text_muted #8B98A8`, `accent #58A6FF`, `accent_dim #1F3A5F`,
  `on_accent #08121F`, `danger #F85149`, `warn #D29922`, `ok #3FB950`.
- Tema `Theme.SuperDownloader` = `Theme.Material3.Dark.NoActionBar`.
- Pagina com `10dp` de margem; titulo de secao solto (12sp, ALL CAPS, bold,
  `letterSpacing 0.09`, muted, `padding(10,2,10,6)`, 6dp abaixo) acima do cartao;
  cartao `bg_card` (surface, 12dp, 1dp de borda, 6dp de padding); linha em
  `rowSlot` (10dp lateral, 4dp vertical, filho de 42dp); 10dp entre blocos.
- Bloco de status: 54dp de altura, titulo 15sp centralizado, com a linha de
  detalhe abaixo quando ha o que dizer.
- History: linha de 72dp quando tem miniatura (imagem de 64dp, canto
  esquerdo) e 42dp quando nao tem; o cartao cresce de baixo para cima.
- Chip: 34dp, 12dp de raio; selecionado = `accent` cheio com texto `on_accent`;
  os outros = `bg_action` com texto muted.
- Barra de link = `timeLogBar` do Super Capture: campo de 42dp com `bg_field`,
  14sp, 12dp de padding interno; botao accent de 88dp ao lado com glifo +
  palavra (largura fixa: `WRAP_CONTENT` nao soma o padding do texto).

## Copy: Title Case em rotulo, sentence case em descricao

Regra do usuario: **tudo que e rotulo tem a primeira letra de cada palavra em
maiuscula**; descricao, hint e mensagem de erro ficam em sentence case.

- Rotulos: `Paste`, `Paste Link Here` (placeholder), `Download`, `Share`,
  `Cancel`, `Try Again`, `Automatic Quality`, `Download Folder`, `No History`,
  e os estados `Waiting For Download` / `Download Complete`.
- Descricoes: hints dos cartoes, `Reading the link…`, `connecting…`, mensagens de
  erro, e a linha de detalhe do status.
- Nenhuma string visivel em portugues: as mensagens de `Downloader`,
  `Resolver` e `Transcode` aparecem na linha de detalhe do status.

## Build

```bash
./build.bat assembleDebug
```

- JDK 21 Temurin, AGP 8.7.3, Kotlin 2.1.0, compileSdk 35, minSdk 29.
- `build.bat` exige `JAVA_HOME` e `ANDROID_HOME` no ambiente e compila a partir da
  propria pasta. Para caminhos fixos de uma maquina, use `build.local.bat` (fora do
  controle de versao) — e o que roda no dia a dia do desenvolvimento.
- Se o projeto estiver numa pasta sincronizada (Google Drive, OneDrive), aponte
  `SD_INIT_SCRIPT` para um init script que mova `buildDir` para fora dela: o
  resource merger falha com `AccessDeniedException` nos arquivos intermediarios.
- APK padrao: `app/build/outputs/apk/debug/app-debug.apk` (ou o caminho definido
  pelo init script).
- `ffmpeg-kit` vem de `dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl` (o
  `com.arthenica` saiu do Maven Central em 2025).

## Teste no aparelho

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://x.com/<user>/status/<id>" \
  -n app.skypper.superdownloader/.MainActivity
```

`Automatic Quality` ligado baixa na hora; desligado o app carrega o link e
espera o toque em `Download` (o status fica em `Waiting for Download`).
Arquivo em `/sdcard/Download/Super Downloader/<usuario>_<mediaId>_<preset>.mp4`.

## Limite do WhatsApp (nao e 16 MB)

O FAQ oficial diz **64 MB** (conexao lenta) / 100 MB (rapida) para video enviado
como midia, com teto de 720p; documento vai a 2 GB sem recompressao. O app usa
**62 MB** de orcamento. Blog de terceiro repetindo "16 MB" se refere a foto e
status, nao a video — com o numero errado o app recomprimia video que caberia.

## Auditoria (estado do codigo)

Feita com varredura estatica; o que sobrou de fora:

- Sem funcao privada sem uso, sem membro publico sem uso, sem drawable orfao,
  sem import sem uso, sem TODO/log de debug.
- Removidos: `cardParams`, `Dest.display`, `bestFor`/`bestHeight`/`maxHeight`,
  `clearDest`, seis acessores da `Palette` e cinco cores do `colors.xml`,
  sete drawables (`bg_chip_selected`, `bg_pill*`, `ic_download`, `ic_link`,
  `ic_retry`, `progress_horizontal`).

Bugs reais que a auditoria encontrou (e que o build verde nao mostrava):

- **A barra `Download Folder:` abria o Share Quality**, nao o seletor de pasta:
  um replace em massa trocou o listener dos dois. `folderLauncher` ficou sem
  nenhuma chamada — o sintoma que denunciou.
- **`fromShare` lia `Activity.intent`**, que `onNewIntent` NAO atualiza: o
  segundo share era classificado com a action da primeira abertura. Agora a
  action vem por parametro de `handle(url, isShare)`.
- **A copia para o cache no transcode nunca era apagada**: cada compressao
  deixava o original (dezenas de MB) em `cacheDir/src`.

## Armadilhas conhecidas

- O nome do arquivo usa o **media id**, nao o id do tweet — sao diferentes no X
  (`melt_simp_2076784277404991489.mp4` para o tweet `2114510211444527226`).
- `minSdk 29` e proposital: `MediaStore.Downloads` + `IS_PENDING` dispensam
  permissao de armazenamento e evitam arquivo pela metade na galeria.
- `launchMode=singleTask`: um segundo link chega em `onNewIntent`, que cancela o
  download anterior e enfileira no mesmo executor de uma thread.
- **Iterar `childCount` de uma fileira mista quebra o app.** `paintChips` fazia
  `getChildAt(i) as TextView` em todos os filhos da fileira de qualidade; quando
  a engrenagem (FrameLayout) entrou no fim dela, o launch morreu com
  `ClassCastException: FrameLayout cannot be cast to TextView`. Percorra os
  indices do modelo (`Quality.ALL.indices`) e use `as?`.
- **Um `setDetail` que chama a si mesmo mata o app no launch** (`StackOverflowError`,
  sem aviso no build). Um regex que trocou `detailView.text = x` por
  `setDetail(x)` tambem reescreveu o corpo do proprio helper. Depois de qualquer
  varredura dessas, ler o corpo do helper antes de confiar.
- **`uiautomator dump` reporta `bounds="[0,0][0,0]"`** para o rotulo da tab bar e
  para o botao de acao. E limitacao do dump, nao layout quebrado: confirme por
  `screencap` antes de "consertar".
- **`WRAP_CONTENT` de um `LinearLayout` horizontal nao soma o padding do texto**:
  um botao com `WRAP_CONTENT` fechava 30dp antes da margem da pagina. Largura fixa
  em dp resolve.
- O share sheet assume a tela. Quando `mCurrentFocus` responde outro pacote
  (WhatsApp `ExternalShareAlias`), o usuario esta compartilhando de verdade:
  verifique por artefato em vez de continuar tocando a tela.
- **O X so publica poucas alturas.** Num post 720x1280 existem 320/480/720 no
  `master.m3u8`; 4K/1440p/1080p nao existem. A escada do Custom corta no maximo do
  post (`Quality.ladderFor`) e `Downloader.pick` cai para o maior disponivel.
- **O x264 nao entrega o bitrate pedido.** Uma passada pedindo 2.500k num video de
  85 s gerou 26,5 MB contra 15 MB de orcamento. `Transcode` mede o arquivo e faz
  uma segunda passada corrigida pelo tamanho real.
