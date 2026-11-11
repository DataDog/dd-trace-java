-- Upstream-service request headers: every request continues a distributed trace, so the tracer
-- runs extraction (W3C + Datadog styles, tracestate, propagation tags, baggage) on each one.
-- IDs are built from 24-bit pieces so hex and decimal stay exact in LuaJIT doubles.
local thread_counter = 0
function setup(thread)
  thread_counter = thread_counter + 1
  thread:set("seed", os.time() * 1000 + thread_counter)
end

function init(args)
  math.randomseed(seed or os.time())
end

local function id64()
  -- below 2^53 so the decimal form is exact; never zero
  local a, b, c = math.random(0, 31), math.random(0, 16777215), math.random(1, 16777215)
  return string.format("%04x%06x%06x", a, b, c), string.format("%.0f", a * 2^48 + b * 2^24 + c)
end

local function hex64()
  return string.format("%04x%06x%06x", math.random(0, 65535), math.random(0, 16777215), math.random(0, 16777215))
end

request = function()
  local hi = hex64()
  local lo_hex, lo_dec = id64()
  local span_hex, span_dec = id64()
  local h = wrk.headers
  h["traceparent"] = "00-" .. hi .. lo_hex .. "-" .. span_hex .. "-01"
  h["tracestate"] = "dd=s:1;o:rum;p:" .. span_hex .. ";t.dm:-4;t.tid:" .. hi .. ",othervendor=t61rcWkgMzE"
  h["x-datadog-trace-id"] = lo_dec
  h["x-datadog-parent-id"] = span_dec
  h["x-datadog-sampling-priority"] = "1"
  h["x-datadog-origin"] = "rum"
  h["x-datadog-tags"] = "_dd.p.dm=-4,_dd.p.tid=" .. hi
  h["baggage"] = "user.id=4417,session.id=a9f3c2,tenant=acme"
  return wrk.format()
end
