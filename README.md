# Tradutor Fala

Traduz e **fala a tradução em segundo plano**: continua falando mesmo com você
em outro app, outra aba, outra pasta ou com a tela apagada.

## 1. App Android (Google ML Kit + voz do Android)

- Tradução com o **Google ML Kit**: grátis e **offline** depois de baixar o idioma
  (só a 1ª vez precisa de internet).
- Fala com a voz do próprio Android, dentro de um **serviço em primeiro plano**
  (notificação fixa com Repetir / Parar / Fechar). Por isso não é cortado.
- Entrada por texto digitado ou pelo **microfone**.
- Em **qualquer app**: selecione um texto → menu ⋮ → **"Traduzir e falar"**
  (ou Compartilhar → Tradutor Fala). Você continua no app e ouve a tradução.

### Como baixar o APK
1. No GitHub, abra a aba **Actions** → workflow **"Gerar APK"** → a execução mais recente.
2. Em **Artifacts**, baixe **TradutorFala-apk** (é um .zip; dentro está o `app-debug.apk`).
3. Instale no celular (permita "instalar apps desconhecidos").
4. Abra o app, permita as **notificações** e, se quiser garantir, vá em
   Configurações → Apps → Tradutor Fala → Bateria → **Sem restrições**.

## 2. Script Python `tradutor_fala.py` (Termux)

O ML Kit não existe para Python, então o script usa o Google Tradutor online.

```
pkg install python termux-api      # precisa dos apps Termux e Termux:API (F-Droid)
python tradutor_fala.py            # digite, ele traduz e fala
python tradutor_fala.py --copiar   # copie texto em qualquer app -> traduz e fala
python tradutor_fala.py -o en -d pt
```
