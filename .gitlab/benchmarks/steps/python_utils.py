import numpy as np
import scipy.stats as st


def _map_measurement(value: float, source_uom: str, target_uom: str) -> float:
    if source_uom == target_uom:
        return value
    source_ns = value * _nanoseconds_factor(source_uom)
    return source_ns / _nanoseconds_factor(target_uom)


def _nanoseconds_factor(from_uom: str) -> float:
    if from_uom == 's':
        return 1000000000
    elif from_uom == 'ms':
        return 1000000
    elif from_uom == 'µs':
        return 1000
    else:
        return 1


def round_value(value: float, uom: str) -> str:
    if abs(value) >= 1000 and uom != 's':
        if uom == 'ns':
            target_uom = 'µs'
        elif uom == 'µs':
            target_uom = 'ms'
        else:
            target_uom = 's'
        return round_value(_map_measurement(value, uom, target_uom), target_uom)
    else:
        return f"{round(value, 3)} {uom}"


def import_benchmark_values(benchmark: dict, measure: str, target_uom: str) -> list[float]:
    run_values = list()
    for run in benchmark["runs"].values():
        measurement = run[measure]
        source_uom = measurement["uom"]
        for value in measurement["values"]:
            run_values.append(_map_measurement(value, source_uom, target_uom))
    return run_values


def compute_confidence_interval(values: list[float], interval: float) -> tuple[float, float]:
    if len(values) == 1:
        return (values[0], values[0])
    if len(values) < 100:
        return st.t.interval(confidence=interval,
                             df=len(values) - 1,
                             loc=np.mean(values),
                             scale=st.sem(values))
    else:
        return st.norm.interval(confidence=interval,
                                loc=np.mean(values),
                                scale=st.sem(values))
