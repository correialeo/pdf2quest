#!/usr/bin/env bash
# Sobe o PDF2Questao e abre o navegador em http://localhost:8080. Para parar: Ctrl+C nesta janela.
set -euo pipefail
cd "$(dirname "$0")"

PORTA="${PORTA:-8080}"
URL="http://localhost:$PORTA"

java21_ok() {
    command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -Eq 'version "(2[1-9]|[3-9][0-9])'
}

# No Mac o Java do Homebrew nao entra no PATH sozinho.
if ! java21_ok && command -v brew >/dev/null 2>&1 && brew --prefix openjdk@21 >/dev/null 2>&1; then
    export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! java21_ok || ! command -v mvn >/dev/null 2>&1; then
    echo "Falta o Java 21 ou o Maven. Rode primeiro:  ./instalar.sh" >&2
    exit 1
fi

# Abre o navegador assim que o servidor responder (em segundo plano, para nao travar o terminal).
(
    for _ in $(seq 1 180); do
        if curl -s -o /dev/null "$URL"; then
            if command -v open >/dev/null 2>&1; then open "$URL"
            elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL" >/dev/null 2>&1
            fi
            exit 0
        fi
        sleep 1
    done
) &

echo "Iniciando o PDF2Questao em $URL (a primeira vez demora alguns minutos: o Maven baixa as dependencias)."
echo "Para parar, aperte Ctrl+C."
exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$PORTA"
