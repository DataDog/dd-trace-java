import math


def round_value(value, uom):
    for unit in ("s", "ms", "µs", "ns"):
        if value >= 100.0 or unit == uom:
            return f"{round(value, 1)} {unit}"
        value *= 1_000
    return f"{value} ns"


def compute_confidence_interval(values, confidence):
    n = len(values)
    values = sorted(values)
    lower = int(math.floor((1.0 - confidence) / 2.0 * n))
    upper = int(math.ceil((1.0 + confidence) / 2.0 * n))
    return values[lower], values[upper - 1]


def import_benchmark_values(benchmark, metric, output_uom):
    values = list()
    for run in benchmark["runs"].values():
        values += run[metric]["values"]
        input_uom = run[metric]["uom"]
        if input_uom == "ns" and output_uom == "µs":
            values = [v / 1_000 for v in values]
        if input_uom == "µs" and output_uom == "ms":
            values = [v / 1_000 for v in values]
    return values
