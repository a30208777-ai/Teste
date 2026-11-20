#!/usr/bin/env python3
"""
tradutor_fala.py — traduz e FALA o texto, continuando em segundo plano
(mesmo com você em outro app, aba, pasta ou com a tela apagada).

Feito para o Termux no Android. Também funciona no PC (Windows/Linux/Mac).

Observação: o Google ML Kit só existe dentro de apps Android (Kotlin/Java), não
em Python. Por isso este script usa o Google Tradutor online (precisa de internet).
O app Android deste repositório é que usa o ML Kit offline.

INSTALAR NO ANDROID (uma vez só):
  1. Instale pelo F-Droid: "Termux" e "Termux:API" (os dois do F-Droid!).
  2. No Termux:   pkg install python termux-api
  3. Copie este arquivo para o celular e rode:  python tradutor_fala.py

MODOS:
  python tradutor_fala.py                      -> você digita, ele traduz e fala
  python tradutor_fala.py --copiar             -> copie texto em QUALQUER app e ele
                                                  traduz e fala sozinho
  python tradutor_fala.py -o en -d pt          -> escolher idiomas (origem/destino)
  python tradutor_fala.py "Hello world"        -> traduz e fala só esse texto

Para não ser cortado em segundo plano: Configurações → Apps → Termux → Bateria
→ "Sem restrições". O script já ativa o "termux-wake-lock" sozinho.
"""

import argparse
import json
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

NO_TERMUX = shutil.which("termux-tts-speak") is not None
_fala_atual = None  # processo de fala em andamento
_trava = threading.Lock()


# ---------------------------- Tradução ----------------------------

def traduzir(texto, origem="auto", destino="pt"):
    """Tradução pelo endpoint público do Google Tradutor (sem chave)."""
    # Divide textos grandes em blocos (limite da URL)
    partes, bloco = [], ""
    for frase in texto.split(". "):
        if bloco and len(bloco) + len(frase) > 600:
            partes.append(bloco)
            bloco = ""
        bloco = f"{bloco}. {frase}" if bloco else frase
    if bloco.strip():
        partes.append(bloco)

    resultado = []
    for parte in partes:
        url = (
            "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t"
            f"&sl={origem}&tl={destino}&q={urllib.parse.quote(parte)}"
        )
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as r:
            dados = json.loads(r.read().decode("utf-8"))
        resultado.append("".join(seg[0] for seg in dados[0] if seg and seg[0]))
    return " ".join(resultado).strip()


# ------------------------------ Fala ------------------------------

def parar_fala():
    global _fala_atual
    with _trava:
        if _fala_atual and _fala_atual.poll() is None:
            _fala_atual.terminate()
        _fala_atual = None


def falar(texto, idioma="pt"):
    """Fala o texto. No Termux usa a voz do Android (continua em segundo plano)."""
    global _fala_atual
    parar_fala()
    if NO_TERMUX:
        cmd = ["termux-tts-speak", "-l", idioma, "-s", "MUSIC", texto]
        with _trava:
            _fala_atual = subprocess.Popen(cmd)
        return
    try:  # PC: pip install pyttsx3
        import pyttsx3
        motor = pyttsx3.init()
        motor.say(texto)
        motor.runAndWait()
    except ImportError:
        print("  (para ouvir no PC instale: pip install pyttsx3)")


# --------------------------- Utilidades ---------------------------

def manter_acordado():
    if shutil.which("termux-wake-lock"):
        subprocess.run(["termux-wake-lock"], check=False)
    if shutil.which("termux-notification"):
        subprocess.run([
            "termux-notification", "--id", "tradutor_fala", "--ongoing",
            "--title", "Tradutor Fala ativo",
            "--content", "Traduzindo e falando em segundo plano",
            "--button1", "Parar fala", "--button1-action", "pkill -f termux-tts-speak",
        ], check=False)


def liberar():
    if shutil.which("termux-wake-unlock"):
        subprocess.run(["termux-wake-unlock"], check=False)
    if shutil.which("termux-notification-remove"):
        subprocess.run(["termux-notification-remove", "tradutor_fala"], check=False)


def ler_area_transferencia():
    try:
        if NO_TERMUX:
            return subprocess.run(["termux-clipboard-get"], capture_output=True,
                                  text=True, timeout=10).stdout.strip()
        import tkinter  # PC
        r = tkinter.Tk()
        r.withdraw()
        t = r.clipboard_get()
        r.destroy()
        return t.strip()
    except Exception:
        return ""


def processar(texto, origem, destino):
    try:
        traduzido = traduzir(texto, origem, destino)
    except Exception as e:
        print(f"  Erro ao traduzir (tem internet?): {e}")
        return
    print(f"  -> {traduzido}")
    falar(traduzido, destino)


# ------------------------------ Modos ------------------------------

def modo_digitar(origem, destino):
    print(f"Tradutor Fala ({origem} -> {destino}). Digite o texto e Enter.")
    print("Comandos: /parar  /trocar  /sair\n")
    while True:
        try:
            texto = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not texto:
            continue
        if texto == "/sair":
            break
        if texto == "/parar":
            parar_fala()
            continue
        if texto == "/trocar":
            if origem == "auto":
                print("  Defina a origem com -o para poder trocar.")
            else:
                origem, destino = destino, origem
                print(f"  Agora: {origem} -> {destino}")
            continue
        # traduz em outra thread para você poder continuar digitando
        threading.Thread(target=processar, args=(texto, origem, destino), daemon=True).start()


def modo_copiar(origem, destino, intervalo=1.0):
    print(f"Modo COPIAR ({origem} -> {destino}).")
    print("Vá para qualquer app, selecione um texto e toque em 'Copiar'.")
    print("Ele será traduzido e falado. Ctrl+C para sair.\n")
    ultimo = ler_area_transferencia()
    vazio_avisado = False
    while True:
        try:
            time.sleep(intervalo)
            atual = ler_area_transferencia()
            if not atual and not vazio_avisado and NO_TERMUX:
                print("  Aviso: não consegui ler a área de transferência.\n"
                      "  No Android 10+ isso pode ser bloqueado em segundo plano;\n"
                      "  se acontecer, use o app 'Tradutor Fala' (menu 'Traduzir e falar').")
                vazio_avisado = True
            if atual and atual != ultimo:
                ultimo = atual
                print(f"Copiado: {atual[:80]}{'…' if len(atual) > 80 else ''}")
                processar(atual, origem, destino)
        except KeyboardInterrupt:
            break


def main():
    p = argparse.ArgumentParser(description="Traduz e fala em segundo plano.")
    p.add_argument("texto", nargs="*", help="texto para traduzir (opcional)")
    p.add_argument("-o", "--origem", default="auto", help="idioma de origem (auto, en, es…)")
    p.add_argument("-d", "--destino", default="pt", help="idioma de destino (pt, en, es…)")
    p.add_argument("--copiar", action="store_true",
                   help="traduz e fala tudo que você copiar em qualquer app")
    a = p.parse_args()

    if not NO_TERMUX:
        print("(Termux não detectado: rodando no modo PC)")
    manter_acordado()
    try:
        if a.texto:
            processar(" ".join(a.texto), a.origem, a.destino)
            if _fala_atual:
                _fala_atual.wait()
        elif a.copiar:
            modo_copiar(a.origem, a.destino)
        else:
            modo_digitar(a.origem, a.destino)
    finally:
        liberar()
    print("\nAté mais!")


if __name__ == "__main__":
    sys.exit(main())
