"""
SIMULACAO DIDATICA da API do PySpark, sobre pandas. NAO e o Spark: nao ha JVM, cluster nem particoes de verdade.

Serve para ver o que a prova cobra: transformacao (lazy, so monta o plano) x acao (dispara um job), quantos jobs e
stages cada acao custaria (cada shuffle -- groupBy, join, orderBy, distinct, repartition -- abre um stage novo), o
plano com explain() e o efeito de cache()/persist(). Os numeros de jobs/stages seguem a regra do livro (1 job por
acao, 1 + numero de shuffles stages), nao o que um Spark real faria em todos os casos (ele as vezes dispara jobs
extras, ex.: amostragem do orderBy, e o AQE junta particoes).
"""
import sys
import types

import pandas as pd

DEFAULT_PARTITIONS = 4        # simulado: particoes de um DataFrame criado na sessao
SHUFFLE_PARTITIONS = 200      # spark.sql.shuffle.partitions (padrao do Spark)

_stats = {"jobs": 0, "stages": 0, "acoes": []}


def stats():
    return dict(_stats, simulacao=True)


def _reset():
    _stats.update(jobs=0, stages=0, acoes=[])


# ---------------------------------------------------------------- colunas e funcoes

class Column:
    __hash__ = None

    def __init__(self, fn, name, text=None, agg=None, order=None):
        self._fn = fn
        self._name = name
        self._text = text or name
        self._agg = agg          # (funcao pandas, coluna de dentro) nas agregacoes
        self._order = order      # "asc"/"desc" no orderBy

    def _eval(self, pdf):
        return self._fn(pdf)

    def __repr__(self):
        return "Column<'%s'>" % self._text

    def alias(self, name):
        return Column(self._fn, name, "%s AS %s" % (self._text, name), self._agg)

    name = alias

    def _bin(self, other, op, sym):
        o = other if isinstance(other, Column) else lit(other)
        return Column(lambda d: op(self._eval(d), o._eval(d)), "(%s %s %s)" % (self._name, sym, o._name),
                      "(%s %s %s)" % (self._text, sym, o._text))

    def __add__(self, o): return self._bin(o, lambda a, b: a + b, "+")
    def __radd__(self, o): return lit(o)._bin(self, lambda a, b: a + b, "+")
    def __sub__(self, o): return self._bin(o, lambda a, b: a - b, "-")
    def __rsub__(self, o): return lit(o)._bin(self, lambda a, b: a - b, "-")
    def __mul__(self, o): return self._bin(o, lambda a, b: a * b, "*")
    def __rmul__(self, o): return lit(o)._bin(self, lambda a, b: a * b, "*")
    def __truediv__(self, o): return self._bin(o, lambda a, b: a / b, "/")
    def __rtruediv__(self, o): return lit(o)._bin(self, lambda a, b: a / b, "/")
    def __mod__(self, o): return self._bin(o, lambda a, b: a % b, "%")
    def __eq__(self, o): return self._bin(o, lambda a, b: a == b, "=")
    def __ne__(self, o): return self._bin(o, lambda a, b: a != b, "!=")
    def __lt__(self, o): return self._bin(o, lambda a, b: a < b, "<")
    def __le__(self, o): return self._bin(o, lambda a, b: a <= b, "<=")
    def __gt__(self, o): return self._bin(o, lambda a, b: a > b, ">")
    def __ge__(self, o): return self._bin(o, lambda a, b: a >= b, ">=")
    def __and__(self, o): return self._bin(o, lambda a, b: a & b, "AND")
    def __or__(self, o): return self._bin(o, lambda a, b: a | b, "OR")

    def __invert__(self):
        return Column(lambda d: ~self._eval(d), "(NOT %s)" % self._name, "(NOT %s)" % self._text)

    def __bool__(self):
        raise ValueError("Use '&' para 'and', '|' para 'or' e '~' para 'not' ao combinar condições de coluna.")

    def isin(self, *values):
        vals = list(values[0]) if len(values) == 1 and isinstance(values[0], (list, tuple, set)) else list(values)
        return Column(lambda d: self._eval(d).isin(vals), "(%s IN %s)" % (self._name, vals))

    def isNull(self):
        return Column(lambda d: self._eval(d).isna(), "(%s IS NULL)" % self._name)

    def isNotNull(self):
        return Column(lambda d: self._eval(d).notna(), "(%s IS NOT NULL)" % self._name)

    def between(self, low, high):
        return (self >= low) & (self <= high)

    def cast(self, kind):
        k = str(kind).lower()
        conv = (lambda s: s.astype(float)) if any(t in k for t in ("double", "float", "decimal")) else \
            (lambda s: s.astype("int64")) if any(t in k for t in ("int", "long")) else (lambda s: s.astype(str))
        return Column(lambda d: conv(self._eval(d)), self._name, "CAST(%s AS %s)" % (self._text, k))

    def desc(self):
        return Column(self._fn, self._name, self._text + " DESC", order="desc")

    def asc(self):
        return Column(self._fn, self._name, self._text + " ASC", order="asc")

    def otherwise(self, value):
        raise ValueError("otherwise() só depois de when().")


class _When(Column):
    def __init__(self, cases, default=None):
        self._cases = cases
        self._default = default
        text = "CASE " + " ".join("WHEN %s THEN %s" % (c._text, v._text) for c, v in cases) + \
               ("" if default is None else " ELSE %s" % default._text) + " END"
        super().__init__(self._run, text, text)

    def _run(self, d):
        out = pd.Series([None] * len(d), index=d.index, dtype=object) if self._default is None \
            else self._default._eval(d).astype(object)
        for cond, val in reversed(self._cases):
            mask = cond._eval(d).fillna(False).astype(bool)
            out = out.where(~mask, val._eval(d))
        try:
            return pd.to_numeric(out)
        except (ValueError, TypeError):
            return out

    def when(self, cond, value):
        return _When(self._cases + [(cond, _c(value))], self._default)

    def otherwise(self, value):
        return _When(self._cases, _c(value))


def _c(x):
    if isinstance(x, Column):
        return x
    return lit(x)


def col(name):
    if name == "*":
        return Column(lambda d: pd.Series([1] * len(d), index=d.index), "*")
    return Column(lambda d: d[name], name)


column = col


def lit(value):
    return Column(lambda d: pd.Series([value] * len(d), index=d.index), str(value), repr(value))


def when(cond, value):
    return _When([(cond, _c(value))])


def _colarg(x):
    return col(x) if isinstance(x, str) else x


def _agg(func, c, label):
    inner = _colarg(c)
    name = "%s(%s)" % (label, inner._name)
    return Column(inner._fn, name, name, agg=(func, inner))


def sum(c): return _agg("sum", c, "sum")  # noqa: A001 - nome da API do Spark
def avg(c): return _agg("mean", c, "avg")
def mean(c): return _agg("mean", c, "avg")
def max(c): return _agg("max", c, "max")  # noqa: A001
def min(c): return _agg("min", c, "min")  # noqa: A001
def count(c): return _agg("count" if c != "*" else "size", c, "count")
def countDistinct(c): return _agg("nunique", c, "count(DISTINCT")


def round(c, scale=0):  # noqa: A001
    inner = _colarg(c)
    return Column(lambda d: inner._eval(d).round(scale), "round(%s, %d)" % (inner._name, scale))


def upper(c):
    inner = _colarg(c)
    return Column(lambda d: inner._eval(d).str.upper(), "upper(%s)" % inner._name)


def lower(c):
    inner = _colarg(c)
    return Column(lambda d: inner._eval(d).str.lower(), "lower(%s)" % inner._name)


def desc(c): return _colarg(c).desc()
def asc(c): return _colarg(c).asc()


# ---------------------------------------------------------------- linhas, niveis de armazenamento

class Row(tuple):
    def __new__(cls, *args, **kwargs):
        if kwargs:
            row = tuple.__new__(cls, kwargs.values())
            row._fields = list(kwargs.keys())
            return row
        return tuple.__new__(cls, args)

    def __getattr__(self, item):
        fields = self.__dict__.get("_fields", [])
        if item in fields:
            return self[fields.index(item)]
        raise AttributeError(item)

    def __getitem__(self, item):
        if isinstance(item, str):
            return self[self._fields.index(item)]
        return tuple.__getitem__(self, item)

    def asDict(self):
        return dict(zip(self._fields, self))

    def __repr__(self):
        fields = self.__dict__.get("_fields")
        if not fields:
            return "<Row(%s)>" % ", ".join(repr(v) for v in self)
        return "Row(%s)" % ", ".join("%s=%r" % (k, _py(v)) for k, v in zip(fields, self))


def _py(v):
    return v.item() if hasattr(v, "item") else v


class StorageLevel:
    def __init__(self, name):
        self.name = name

    def __repr__(self):
        return "StorageLevel." + self.name


for _lvl in ("MEMORY_ONLY", "MEMORY_AND_DISK", "DISK_ONLY", "MEMORY_ONLY_SER", "MEMORY_AND_DISK_SER", "OFF_HEAP"):
    setattr(StorageLevel, _lvl, StorageLevel(_lvl))


# ---------------------------------------------------------------- DataFrame (plano lazy)

class _RDD:
    def __init__(self, df):
        self._df = df

    def getNumPartitions(self):
        return self._df._partitions


class DataFrame:
    def __init__(self, op, parents, fn, partitions, wide=False):
        self._op = op                # texto do no do plano
        self._parents = parents
        self._fn = fn                # (lista de pandas dos pais) -> pandas
        self._partitions = partitions
        self._wide = wide            # shuffle (abre stage novo)
        self._level = None           # cache/persist
        self._cache = None

    # -- infraestrutura
    def _child(self, op, fn, wide=False, partitions=None, parents=None):
        return DataFrame(op, parents or [self], fn, partitions or (SHUFFLE_PARTITIONS if wide else self._partitions),
                         wide)

    def _compute(self):
        if self._cache is not None:
            return self._cache
        res = self._fn([p._compute() for p in self._parents]).reset_index(drop=True)
        if self._level is not None:
            self._cache = res
        return res

    def _shuffles(self):
        if self._cache is not None:
            return 0                 # ja materializado: a acao comeca daqui
        return (1 if self._wide else 0) + builtins_sum(p._shuffles() for p in self._parents)

    def _action(self, name):
        stages = 1 + self._shuffles()
        _stats["jobs"] += 1
        _stats["stages"] += stages
        _stats["acoes"].append({"acao": name, "job": _stats["jobs"], "stages": stages,
                                "cache": self._level is not None and self._cache is not None})
        return self._compute()

    @property
    def columns(self):
        return list(self._schema())

    def _schema(self):
        # So o esquema, sem disparar job (como no Spark, que resolve o esquema no plano).
        return self._compute().columns

    @property
    def rdd(self):
        return _RDD(self)

    @property
    def storageLevel(self):
        return self._level or StorageLevel("NONE")

    @property
    def write(self):
        raise NotImplementedError("Simulação: write não grava arquivos (use show(), collect() ou toPandas()).")

    # -- transformacoes (lazy: so montam o plano)
    def select(self, *cols):
        cols = [_colarg(c) for c in (cols[0] if len(cols) == 1 and isinstance(cols[0], list) else cols)]

        def fn(ps):
            d = ps[0]
            if any(c._name == "*" for c in cols):
                return d.copy()
            return pd.DataFrame({c._name: c._eval(d).values for c in cols})
        return self._child("Project [%s]" % ", ".join(c._text for c in cols), fn)

    def filter(self, cond):
        if isinstance(cond, str):
            raise NotImplementedError("Simulação: use colunas em filter (ex.: F.col('valor') > 100), não texto SQL.")
        return self._child("Filter %s" % cond._text,
                           lambda ps: ps[0][cond._eval(ps[0]).fillna(False).astype(bool).values])

    where = filter

    def withColumn(self, name, c):
        def fn(ps):
            d = ps[0].copy()
            d[name] = c._eval(d).values
            return d
        return self._child("Project [*, %s AS %s]" % (c._text, name), fn)

    def withColumnRenamed(self, old, new):
        return self._child("Project [%s AS %s]" % (old, new), lambda ps: ps[0].rename(columns={old: new}))

    def drop(self, *names):
        return self._child("Project [-%s]" % ", ".join(names), lambda ps: ps[0].drop(columns=list(names)))

    def groupBy(self, *cols):
        return GroupedData(self, [_colarg(c) for c in (cols[0] if len(cols) == 1 and isinstance(cols[0], list)
                                                        else cols)])

    groupby = groupBy

    def orderBy(self, *cols, ascending=True):
        cols = [_colarg(c) for c in cols]
        asc_list = [c._order != "desc" if c._order else ascending for c in cols]

        def fn(ps):
            d = ps[0].copy()
            keys = []
            for i, c in enumerate(cols):
                keys.append("__k%d" % i)
                d["__k%d" % i] = c._eval(d).values
            return d.sort_values(keys, ascending=asc_list, kind="stable").drop(columns=keys)
        return self._child("Sort [%s]" % ", ".join(c._text for c in cols), fn, wide=True)

    sort = orderBy

    def join(self, other, on=None, how="inner"):
        how_pd = {"inner": "inner", "left": "left", "left_outer": "left", "right": "right", "right_outer": "right",
                  "outer": "outer", "full": "outer", "full_outer": "outer", "left_semi": "semi",
                  "left_anti": "anti", "cross": "cross"}[how]
        keys = [on] if isinstance(on, str) else on

        def fn(ps):
            a, b = ps
            if how_pd == "semi":
                return a[a.set_index(keys).index.isin(b.set_index(keys).index)]
            if how_pd == "anti":
                return a[~a.set_index(keys).index.isin(b.set_index(keys).index)]
            if how_pd == "cross":
                return a.merge(b, how="cross")
            return a.merge(b, on=keys, how=how_pd)
        return DataFrame("Join %s %s" % (how, keys), [self, other], fn, SHUFFLE_PARTITIONS, wide=True)

    def distinct(self):
        return self._child("Distinct", lambda ps: ps[0].drop_duplicates(), wide=True)

    def dropDuplicates(self, subset=None):
        return self._child("Deduplicate %s" % (subset or "*"), lambda ps: ps[0].drop_duplicates(subset=subset),
                           wide=True)

    def limit(self, n):
        return self._child("Limit %d" % n, lambda ps: ps[0].head(n))

    def union(self, other):
        return DataFrame("Union", [self, other], lambda ps: pd.concat(ps, ignore_index=True),
                         self._partitions + other._partitions)

    unionByName = union

    def repartition(self, n, *cols):
        return self._child("Exchange RoundRobinPartitioning(%d)" % n, lambda ps: ps[0], wide=True, partitions=n)

    def coalesce(self, n):
        # Estreita: junta particoes sem shuffle.
        return self._child("Coalesce %d" % n, lambda ps: ps[0], partitions=builtins_min(n, self._partitions))

    def cache(self):
        return self.persist(StorageLevel.MEMORY_AND_DISK)

    def persist(self, storageLevel=None):
        self._level = storageLevel or StorageLevel.MEMORY_AND_DISK
        return self

    def unpersist(self, blocking=False):
        self._level = None
        self._cache = None
        return self

    # -- acoes (disparam job)
    def count(self):
        return int(len(self._action("count")))

    def collect(self):
        d = self._action("collect")
        return [Row(**{k: _py(v) for k, v in r.items()}) for r in d.to_dict("records")]

    def take(self, n):
        d = self._action("take").head(n)
        return [Row(**{k: _py(v) for k, v in r.items()}) for r in d.to_dict("records")]

    def first(self):
        rows = self.take(1)
        return rows[0] if rows else None

    head = first

    def toPandas(self):
        return self._action("toPandas").copy()

    def show(self, n=20, truncate=True):
        d = self._action("show")
        print(_table(d.head(n), truncate))
        if len(d) > n:
            print("only showing top %d rows" % n)

    # -- inspecao (nao dispara job)
    def printSchema(self):
        d = self._compute()
        print("root")
        for name, dtype in d.dtypes.items():
            kind = "long" if "int" in str(dtype) else "double" if "float" in str(dtype) else \
                "boolean" if "bool" in str(dtype) else "string"
            print(" |-- %s: %s (nullable = true)" % (name, kind))

    def explain(self, extended=False):
        print("== Plano físico (SIMULAÇÃO didática, não é o Spark) ==")
        lines = []
        self._plan(lines, 0)
        print("\n".join(lines))

    def _plan(self, lines, depth):
        pad = "   " * depth
        prefix = "+- " if depth else ""
        cached = " [em cache: %s]" % self._level.name if self._level is not None else ""
        lines.append(pad + prefix + self._op + cached)
        if self._wide and not self._op.startswith("Exchange"):
            depth += 1
            lines.append("   " * depth + "+- Exchange (shuffle: novo stage, %d partições)" % self._partitions)
        for p in self._parents:
            p._plan(lines, depth + 1)

    def __repr__(self):
        return "DataFrame[%s]" % ", ".join(self._compute().columns)


class GroupedData:
    def __init__(self, df, keys):
        self._df = df
        self._keys = keys

    def agg(self, *aggs):
        if len(aggs) == 1 and isinstance(aggs[0], dict):
            aggs = [_agg({"avg": "mean"}.get(f, f), c, f) for c, f in aggs[0].items()]
        keys = self._keys

        def fn(ps):
            d = ps[0]
            work = pd.DataFrame({k._name: k._eval(d).values for k in keys})
            spec = {}
            for i, a in enumerate(aggs):
                if a._agg is None:
                    raise ValueError("agg() recebe funções de agregação (F.sum, F.count, F.avg...), não %s" % a._text)
                func, inner = a._agg
                work["__v%d" % i] = inner._eval(d).values
                spec[a._name] = ("__v%d" % i, func)
            if not keys:
                return pd.DataFrame({name: [getattr(work[src], f)() if f != "size" else len(work)]
                                     for name, (src, f) in spec.items()})
            return work.groupby([k._name for k in keys], sort=False, dropna=False).agg(**spec).reset_index()
        text = "HashAggregate keys=[%s], funções=[%s]" % (", ".join(k._text for k in keys),
                                                         ", ".join(a._text for a in aggs))
        return self._df._child(text, fn, wide=bool(keys))

    def count(self):
        return self.agg(count("*").alias("count"))

    def sum(self, *cols): return self.agg(*[sum(c) for c in cols])  # noqa: A003
    def avg(self, *cols): return self.agg(*[avg(c) for c in cols])
    def max(self, *cols): return self.agg(*[max(c) for c in cols])  # noqa: A003
    def min(self, *cols): return self.agg(*[min(c) for c in cols])  # noqa: A003


import builtins as _b  # noqa: E402 - as funcoes sum/min/max acima sombreiam as do Python neste modulo

builtins_sum = _b.sum
builtins_min = _b.min


def _fmt(v):
    if v is None or (isinstance(v, float) and v != v):
        return "NULL"
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(_py(v))


def _table(d, truncate=True):
    cols = list(d.columns)
    rows = [[_fmt(v) for v in r] for r in d.itertuples(index=False, name=None)]
    if truncate:
        rows = [[c if len(c) <= 20 else c[:17] + "..." for c in r] for r in rows]
    widths = [_b.max([len(str(c))] + [len(r[i]) for r in rows]) for i, c in enumerate(cols)]
    sep = "+" + "+".join("-" * w for w in widths) + "+"
    out = [sep, "|" + "|".join(str(c).rjust(w) for c, w in zip(cols, widths)) + "|", sep]
    out += ["|" + "|".join(v.rjust(w) for v, w in zip(r, widths)) + "|" for r in rows]
    out.append(sep)
    return "\n".join(out)


# ---------------------------------------------------------------- sessao

class _Reader:
    def __init__(self):
        self._opts = {}

    def option(self, key, value):
        self._opts[key] = value
        return self

    def options(self, **kw):
        self._opts.update(kw)
        return self

    def csv(self, path, header=None, inferSchema=None, sep=None):
        h = header if header is not None else str(self._opts.get("header", "false")).lower() == "true"
        s = sep or self._opts.get("sep", ",")
        infer = inferSchema if inferSchema is not None else \
            str(self._opts.get("inferSchema", "false")).lower() == "true"
        d = pd.read_csv(path, header=0 if h else None, sep=s, dtype=None if infer else str)
        if not h:
            d.columns = ["_c%d" % i for i in range(len(d.columns))]
        return DataFrame("FileScan csv [%s]" % path, [], lambda ps: d.copy(), DEFAULT_PARTITIONS)


class _Builder:
    def appName(self, name): return self
    def master(self, url): return self
    def config(self, *a, **k): return self
    def enableHiveSupport(self): return self

    def getOrCreate(self):
        return SparkSession._active or SparkSession()


class _Conf:
    def __init__(self):
        self._v = {"spark.sql.shuffle.partitions": str(SHUFFLE_PARTITIONS)}

    def set(self, k, v):
        global SHUFFLE_PARTITIONS
        self._v[k] = str(v)
        if k == "spark.sql.shuffle.partitions":
            SHUFFLE_PARTITIONS = int(v)

    def get(self, k, default=None):
        return self._v.get(k, default)


class SparkSession:
    _active = None
    builder = _Builder()

    def __init__(self):
        SparkSession._active = self
        self.conf = _Conf()

    @property
    def read(self):
        return _Reader()

    def createDataFrame(self, data, schema=None):
        if isinstance(data, pd.DataFrame):
            d = data.copy()
        else:
            rows = list(data)
            names = schema
            if isinstance(schema, str):
                names = [p.strip().split()[0] for p in schema.split(",")]
            if rows and isinstance(rows[0], dict):
                d = pd.DataFrame(rows)
            elif rows and isinstance(rows[0], Row) and rows[0].__dict__.get("_fields"):
                d = pd.DataFrame([r.asDict() for r in rows])
            else:
                d = pd.DataFrame(rows, columns=names)
        if isinstance(schema, list) and len(schema) == len(d.columns):
            d.columns = schema
        return DataFrame("Scan [%s]" % ", ".join(map(str, d.columns)), [], lambda ps: d.copy(), DEFAULT_PARTITIONS)

    def range(self, start, end=None, step=1):
        lo, hi = (0, start) if end is None else (start, end)
        d = pd.DataFrame({"id": list(_b.range(lo, hi, step))})
        return DataFrame("Range (%d, %d)" % (lo, hi), [], lambda ps: d.copy(), DEFAULT_PARTITIONS)

    def stop(self):
        SparkSession._active = None


# ---------------------------------------------------------------- "import pyspark" aponta para esta simulacao

def install():
    _reset()
    SparkSession._active = None
    me = sys.modules[__name__]
    pyspark = types.ModuleType("pyspark")
    pyspark.__doc__ = "SIMULAÇÃO didática do PySpark (não é o Spark)."
    pyspark.StorageLevel = StorageLevel
    sql = types.ModuleType("pyspark.sql")
    for name in ("SparkSession", "DataFrame", "Row", "Column", "GroupedData"):
        setattr(sql, name, getattr(me, name))
    functions = types.ModuleType("pyspark.sql.functions")
    for name in ("col", "column", "lit", "when", "sum", "avg", "mean", "max", "min", "count", "countDistinct",
                 "round", "upper", "lower", "desc", "asc"):
        setattr(functions, name, getattr(me, name))
    sql.functions = functions
    typ = types.ModuleType("pyspark.sql.types")
    for name in ("StringType", "IntegerType", "LongType", "DoubleType", "FloatType", "BooleanType", "DateType",
                 "DecimalType"):
        setattr(typ, name, type(name, (), {"__repr__": lambda self, n=name: n + "()",
                                            "__str__": lambda self, n=name: n.replace("Type", "").lower()}))
    sql.types = typ
    storagelevel = types.ModuleType("pyspark.storagelevel")
    storagelevel.StorageLevel = StorageLevel
    pyspark.sql = sql
    sys.modules.update({"pyspark": pyspark, "pyspark.sql": sql, "pyspark.sql.functions": functions,
                        "pyspark.sql.types": typ, "pyspark.storagelevel": storagelevel})
