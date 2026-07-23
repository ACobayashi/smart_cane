const $ = (id) => document.getElementById(id);

let watchId = null;

function deviceId() {
  return $("deviceId").value.trim() || "cane_001";
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
  return res.json();
}

async function sendLocation(position) {
  const payload = {
    device_id: deviceId(),
    lat: position.coords.latitude,
    lng: position.coords.longitude,
    accuracy_m: position.coords.accuracy,
    source: "phone",
    timestamp: new Date().toISOString(),
  };
  const saved = await api("/api/locations", {
    method: "POST",
    body: JSON.stringify(payload),
  });
  $("locationText").textContent = `已上传：${saved.lat.toFixed(6)}, ${saved.lng.toFixed(6)}，精度约 ${Math.round(saved.accuracy_m || 0)} 米`;
  refresh();
}

async function sendManualLocation() {
  const payload = {
    device_id: deviceId(),
    lat: Number($("latInput").value),
    lng: Number($("lngInput").value),
    accuracy_m: 30,
    source: "manual",
    timestamp: new Date().toISOString(),
  };
  if (!Number.isFinite(payload.lat) || !Number.isFinite(payload.lng)) {
    $("locationText").textContent = "手动经纬度格式不正确";
    return;
  }
  const saved = await api("/api/locations", {
    method: "POST",
    body: JSON.stringify(payload),
  });
  $("locationText").textContent = `已上传手动位置：${saved.lat.toFixed(6)}, ${saved.lng.toFixed(6)}`;
  refresh();
}

function uploadCurrentLocation() {
  if (!navigator.geolocation) {
    $("locationText").textContent = "当前浏览器不支持定位";
    return;
  }
  navigator.geolocation.getCurrentPosition(sendLocation, (err) => {
    $("locationText").textContent = `定位失败：${err.message}`;
  }, { enableHighAccuracy: true, timeout: 10000 });
}

function toggleWatchLocation() {
  if (watchId !== null) {
    navigator.geolocation.clearWatch(watchId);
    watchId = null;
    $("watchLocation").textContent = "连续上传";
    return;
  }
  if (!navigator.geolocation) {
    $("locationText").textContent = "当前浏览器不支持定位";
    return;
  }
  watchId = navigator.geolocation.watchPosition(sendLocation, (err) => {
    $("locationText").textContent = `连续定位失败：${err.message}`;
  }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 10000 });
  $("watchLocation").textContent = "停止上传";
}

async function sendRisk(kind) {
  const front = kind === "front";
  const payload = {
    device_id: deviceId(),
    risk_type: front ? "front_obstacle" : "ground_drop",
    level: "high",
    direction: front ? "front" : "down",
    sensor: front ? "tof_front" : "tof_down",
    distance_mm: front ? 420 : 1200,
    battery: 88,
    timestamp: new Date().toISOString(),
  };
  const saved = await api("/api/risk-events", {
    method: "POST",
    body: JSON.stringify(payload),
  });
  $("riskText").textContent = `已生成 #${saved.id}：${saved.ai_message}`;
  refresh();
}

function mapPosition(record, bounds) {
  if (record.lat === null || record.lng === null) {
    return { x: 50, y: 50 };
  }
  const lngSpan = Math.max(bounds.maxLng - bounds.minLng, 0.0001);
  const latSpan = Math.max(bounds.maxLat - bounds.minLat, 0.0001);
  return {
    x: ((record.lng - bounds.minLng) / lngSpan) * 86 + 7,
    y: (1 - (record.lat - bounds.minLat) / latSpan) * 86 + 7,
  };
}

function renderMap(records) {
  const map = $("map");
  map.innerHTML = "";
  const located = records.filter((r) => r.lat !== null && r.lng !== null);
  const bounds = located.length ? {
    minLat: Math.min(...located.map((r) => r.lat)),
    maxLat: Math.max(...located.map((r) => r.lat)),
    minLng: Math.min(...located.map((r) => r.lng)),
    maxLng: Math.max(...located.map((r) => r.lng)),
  } : { minLat: 0, maxLat: 1, minLng: 0, maxLng: 1 };

  records.forEach((record) => {
    const pos = mapPosition(record, bounds);
    const dot = document.createElement("div");
    dot.className = `dot ${record.level}`;
    dot.style.left = `${pos.x}%`;
    dot.style.top = `${pos.y}%`;
    dot.title = `${record.risk_type} ${record.ai_message}`;
    map.appendChild(dot);
  });
}

function renderRecords(records) {
  $("count").textContent = `${records.length} 条`;
  $("records").innerHTML = records.map((r) => `
    <article class="record">
      <strong>#${r.id} ${r.level.toUpperCase()} ${r.risk_type}</strong>
      <span>${r.device_id} / ${r.direction} / ${r.distance_mm || "-"} mm / ${r.created_at}</span>
      <p>${r.ai_message}</p>
      <span>${r.lat === null ? "无定位" : `${r.lat.toFixed(6)}, ${r.lng.toFixed(6)}`}</span>
    </article>
  `).join("");
}

async function refresh() {
  const records = await api(`/api/risk-events?device_id=${encodeURIComponent(deviceId())}&limit=100`);
  renderMap(records);
  renderRecords(records);
}

$("sendLocation").addEventListener("click", uploadCurrentLocation);
$("watchLocation").addEventListener("click", toggleWatchLocation);
$("sendManualLocation").addEventListener("click", sendManualLocation);
$("sendFrontRisk").addEventListener("click", () => sendRisk("front"));
$("sendDropRisk").addEventListener("click", () => sendRisk("drop"));
$("refresh").addEventListener("click", refresh);
$("deviceId").addEventListener("change", refresh);

refresh();
setInterval(refresh, 5000);
