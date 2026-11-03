# Execucao dos blocos de codigo das aulas, igual no navegador (Web Worker) e no servidor (Node, ao criar a aula):
# o mesmo Pyodide roda este arquivo, entao o exemplo que passou na criacao da a mesma saida na tela.
# Como no Jupyter: o valor da ultima linha, se for expressao, e mostrado (DataFrame em texto).
import ast
import contextlib
import io
import json
import os
import sys
import traceback

DATA_DIR = "/home/pyodide/dados"


def _show(value):
    try:
        import pandas as pd
        if isinstance(value, (pd.DataFrame, pd.Series)):
            return value.to_string()
    except ImportError:
        pass
    return repr(value)


def _write_datasets(datasets):
    os.makedirs(DATA_DIR, exist_ok=True)
    for name in os.listdir(DATA_DIR):
        os.remove(os.path.join(DATA_DIR, name))
    for name, csv in (datasets or {}).items():
        with open(os.path.join(DATA_DIR, name + ".csv"), "w", encoding="utf-8") as f:
            f.write(csv.strip() + "\n")
    os.chdir("/home/pyodide")


def _exec(code, env, filename):
    tree = ast.parse(code, filename=filename)
    last = None
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        last = tree.body.pop()
    exec(compile(tree, filename, "exec"), env)
    if last is not None:
        value = eval(compile(ast.Expression(last.value), filename, "eval"), env)
        if value is not None:
            print(_show(value))


def _error(exc, filename):
    # So as linhas do codigo da aula (sem o harness), com a mensagem final legivel.
    frames = [f for f in traceback.extract_tb(exc.__traceback__) if f.filename in ("<aula>", "<teste>")]
    where = ""
    if frames:
        f = frames[-1]
        where = ("Linha %d do teste: " if f.filename == "<teste>" else "Linha %d: ") % f.lineno
    elif isinstance(exc, SyntaxError) and exc.lineno:
        where = "Linha %d: " % exc.lineno
    name = type(exc).__name__
    msg = str(exc) or ("o teste falhou" if isinstance(exc, AssertionError) else "")
    return where + name + (": " + msg if msg else "")


def run(request_json):
    req = json.loads(request_json)
    lang = req.get("lang", "python")
    _write_datasets(req.get("datasets"))
    out = io.StringIO()
    env = {"__name__": "__main__"}
    result = {"ok": True, "output": "", "error": None, "testPassed": None, "spark": None}
    if lang == "pyspark":
        import pyspark_sim
        pyspark_sim.install()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
        try:
            _exec(req["code"], env, "<aula>")
        except BaseException as e:  # noqa: BLE001 - o erro do aluno vira mensagem, nao derruba o worker
            result["ok"] = False
            result["error"] = _error(e, "<aula>")
        if result["ok"] and req.get("test"):
            try:
                exec(compile(req["test"], "<teste>", "exec"), env)
                result["testPassed"] = True
            except BaseException as e:  # noqa: BLE001
                result["testPassed"] = False
                result["error"] = _error(e, "<teste>")
    result["output"] = out.getvalue()
    if lang == "pyspark":
        import pyspark_sim
        result["spark"] = pyspark_sim.stats()
    return json.dumps(result)
