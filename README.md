# J.A.R.V.I.S. — assistente de voz para Android

## Como instalar no seu celular

1. Instale o **Android Studio** (gratuito): https://developer.android.com/studio
2. Abra o Android Studio → **Open** → selecione esta pasta `jarvis`. Espere o Gradle sincronizar (na primeira vez demora alguns minutos).
3. No celular: **Configurações → Sobre o telefone →** toque 7x em **Número da versão** para liberar as Opções do desenvolvedor. Depois ative **Depuração USB**.
4. Conecte o celular no USB, escolha ele no topo do Android Studio e clique em **▶ Run**.
5. Aceite as permissões (microfone, contatos, telefone, notificações).

### Ativar a acessibilidade (necessário para WhatsApp, digitar, voltar)
No app, toque em **Acessibilidade** → encontre **Jarvis** → ative.

> Android 13+: se aparecer "configuração restrita", vá em **Configurações → Apps → Jarvis → ⋮ (canto superior) → Permitir configurações restritas** e tente de novo.

### (Opcional) Cérebro com IA
Para o Jarvis entender pedidos fora da lista e responder perguntas, crie uma chave em https://console.anthropic.com e adicione no arquivo `local.properties` (na raiz do projeto):

```
CLAUDE_API_KEY=sk-ant-...
```

Sem a chave, ele funciona normalmente com os comandos abaixo (offline e sem custo).

## Como usar
- **Toque no círculo** e fale um comando, ou
- Ligue **Escuta contínua** e diga **"Jarvis, ..."** a qualquer momento (ou só "Jarvis" e espere ele perguntar).
- Também dá para digitar comandos na caixa de texto, útil para testar.

## Comandos
| Diga | O que acontece |
|---|---|
| "Abra o WhatsApp" / "abrir Instagram" | Abre qualquer app instalado |
| "Manda mensagem pra Maria dizendo chego em 10 minutos" | Abre a conversa e envia (com acessibilidade) |
| "Pesquise receita de bolo no YouTube" / "abre o YouTube e toca Coldplay" | Busca no YouTube |
| "Pesquise previsão do tempo" | Busca no Google |
| "Digite bom dia" | Escreve no campo de texto aberto |
| "Ligue para mãe" | Faz a ligação |
| "Comece a gravar" / "Grave a tela" / "Pare a gravação" | Grava áudio ou tela |
| "Liga a lanterna" / "Desliga a lanterna" | Lanterna |
| "Volte" / "Vá para a tela inicial" / "Que horas são" | Sistema |
| "Pare de ouvir" | Desliga a escuta contínua |

Gravações ficam em `Android/data/com.jarvis.assistant/files/Music` (áudio) e `.../Movies` (tela).

## Limitações conhecidas
- Na escuta contínua o Android toca um "bip" a cada ciclo de escuta em alguns aparelhos. Dá para silenciar abaixando o volume de mídia/sistema.
- Enquanto grava áudio, o microfone fica ocupado: para parar, use o botão vermelho no app.
- Funciona só em Android (o iPhone não permite que um app controle outros).

## Onde mexer no código
- `CommandParser.kt` — frases que ele entende (adicione regras aqui)
- `ActionExecutor.kt` — o que cada comando faz
- `JarvisAccessibilityService.kt` — cliques e digitação em outros apps
- `Jarvis.kt` — fluxo principal + IA de reserva (Claude)
