#!/usr/bin/env bash
# Instala o que o PDF2Questao precisa para rodar: Java 21, Maven e Tesseract (OCR) com o idioma portugues.
# Funciona no macOS (via Homebrew) e no Linux com apt (Ubuntu/Debian) ou dnf (Fedora).
# Pode rodar de novo quantas vezes quiser: o que ja estiver instalado e pulado.
set -euo pipefail

passo() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()    { printf '\033[1;32m  ok:\033[0m %s\n' "$1"; }
erro()  { printf '\033[1;31m  erro:\033[0m %s\n' "$1" >&2; exit 1; }

java21_ok() {
    command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -Eq 'version "(2[1-9]|[3-9][0-9])'
}

instalar_macos() {
    if ! command -v brew >/dev/null 2>&1; then
        passo "Instalando o Homebrew (gerenciador de programas do Mac). Ele vai pedir a senha do computador."
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
        # O Homebrew fica em lugares diferentes no Mac com chip Apple e no Mac Intel.
        if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)"; fi
        if [ -x /usr/local/bin/brew ]; then eval "$(/usr/local/bin/brew shellenv)"; fi
    fi
    ok "Homebrew"

    passo "Instalando Java 21, Maven e Tesseract"
    brew install openjdk@21 maven tesseract tesseract-lang
}

instalar_linux_apt() {
    passo "Instalando Java 21, Maven e Tesseract (vai pedir a senha do computador)"
    sudo apt-get update
    sudo apt-get install -y openjdk-21-jdk maven tesseract-ocr tesseract-ocr-por
}

instalar_linux_dnf() {
    passo "Instalando Java 21, Maven e Tesseract (vai pedir a senha do computador)"
    sudo dnf install -y java-21-openjdk-devel maven tesseract tesseract-langpack-por
}

case "$(uname -s)" in
    Darwin) instalar_macos ;;
    Linux)
        if command -v apt-get >/dev/null 2>&1; then
            instalar_linux_apt
        elif command -v dnf >/dev/null 2>&1; then
            instalar_linux_dnf
        else
            erro "Linux sem apt nem dnf. Instale manualmente: Java 21, Maven e Tesseract com o idioma 'por'."
        fi
        ;;
    *) erro "Sistema nao suportado por este script. No Windows, use o WSL (veja o README)." ;;
esac

passo "Conferindo"
# No Mac o Java do Homebrew nao entra no PATH sozinho; o iniciar.sh tambem faz isso.
if [ "$(uname -s)" = "Darwin" ] && ! java21_ok; then
    export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
java21_ok || erro "Java 21 nao encontrado depois da instalacao."
ok "Java: $(java -version 2>&1 | head -1)"
command -v mvn >/dev/null 2>&1 || erro "Maven nao encontrado depois da instalacao."
ok "Maven: $(mvn -v 2>/dev/null | head -1)"
command -v tesseract >/dev/null 2>&1 || erro "Tesseract nao encontrado depois da instalacao."
tesseract --list-langs 2>/dev/null | grep -qx por || erro "Tesseract instalado, mas sem o idioma portugues (por)."
ok "Tesseract com portugues"

printf '\n\033[1;32mTudo pronto!\033[0m Agora rode:  ./iniciar.sh\n'
