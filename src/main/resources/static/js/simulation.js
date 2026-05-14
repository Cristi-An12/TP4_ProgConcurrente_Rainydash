/**
 * simulation.js — RainyDash frontend
 *
 * Responsibilities:
 *  1. Connect to the Spring WebSocket/STOMP endpoint
 *  2. Receive SimulationStateDTO every 500ms and update all UI panels
 *  3. Render the city map canvas (grid, riders, rain, targets)
 *  4. Render the buffer slots with colour-coded wait times
 *  5. Render the delivery-time histogram
 *  6. REST calls for simulation controls
 */

// ── WebSocket setup ──────────────────────────────────────────────────────
const socket = new SockJS('/ws');
const stompClient = Stomp.over(socket);
stompClient.debug = () => {};  // suppress noisy STOMP log

stompClient.connect({}, () => {
    stompClient.subscribe('/topic/simulation', msg => {
        try {
            const state = JSON.parse(msg.body);
            applyState(state);
        } catch (e) {
            console.error('Failed to parse state', e);
        }
    });
});

// ── Canvas references ────────────────────────────────────────────────────
const cityCanvas = document.getElementById('cityCanvas');
const ctx        = cityCanvas.getContext('2d');
const histCanvas = document.getElementById('histogramCanvas');
const hctx       = histCanvas.getContext('2d');

// City grid constants
const COLS = 6, ROWS = 5;
const CELL_W = cityCanvas.width  / COLS;
const CELL_H = cityCanvas.height / ROWS;

// Rain drops state
const DROPS = Array.from({ length: 80 }, () => ({
    x: Math.random() * cityCanvas.width,
    y: Math.random() * cityCanvas.height,
    speed: 3 + Math.random() * 4
}));

let lastState = null;
let animFrameId = null;

// ── Apply state from WebSocket ───────────────────────────────────────────
function applyState(state) {
    lastState = state;

    updateBadge('status-badge', state.simulationStatus);
    updateBadge('weather-badge', state.weather);

    updateSemaphores(state);
    updateBuffer(state.bufferContents || []);
    updateStats(state);
    updateRiderList(state.riders || []);
    drawHistogram(state.deliveryTimesHistory || []);

    // Trigger canvas redraw
    if (!animFrameId) scheduleFrame();
}

// ── Badges ───────────────────────────────────────────────────────────────
function updateBadge(id, value) {
    const el = document.getElementById(id);
    if (!el) return;
    if (id === 'status-badge') {
        const map = {
            RUNNING: ['▶ RUNNING', 'badge-running'],
            PAUSED:  ['⏸ PAUSED',  'badge-paused'],
            STOPPED: ['⏹ STOPPED', 'badge-stopped']
        };
        const [text, cls] = map[value] || ['?', 'badge-stopped'];
        el.textContent = text;
        el.className = 'badge ' + cls;
    } else {
        if (value === 'RAINY') {
            el.textContent = '🌧️ RAINY';
            el.className = 'badge badge-rainy';
        } else {
            el.textContent = '☀️ SUNNY';
            el.className = 'badge badge-sunny';
        }
    }
}

// ── Semaphores ───────────────────────────────────────────────────────────
function updateSemaphores(state) {
    setText('sem-empty-val',  state.emptySlots);
    setText('sem-filled-val', state.filledSlots);
    setText('sem-mutex-val',  state.mutexLocked ? '🔒 locked' : '🔓 free');

    const emptyCard = document.getElementById('sem-empty');
    const fillCard  = document.getElementById('sem-filled');
    emptyCard.classList.toggle('sem-warn', state.emptySlots === 0);
    emptyCard.classList.toggle('sem-ok',   state.emptySlots > 0);
    fillCard.classList.toggle('sem-warn',  state.filledSlots === 0);
    fillCard.classList.toggle('sem-ok',    state.filledSlots > 0);
}

// ── Buffer slots ─────────────────────────────────────────────────────────
const prevBuffer = {};  // track previous slot occupancy for flash animations

function updateBuffer(slots) {
    const container = document.getElementById('buffer-slots');
    // Build or update slots
    while (container.children.length < slots.length) {
        const div = document.createElement('div');
        div.className = 'buffer-slot slot-empty';
        container.appendChild(div);
    }

    const now = Date.now();

    slots.forEach((order, i) => {
        const div = container.children[i];
        const wasOccupied = prevBuffer[i];
        const isOccupied  = order !== null;

        if (!isOccupied) {
            if (wasOccupied) {
                // Slot just emptied — flash blue (consumer took order)
                div.classList.add('slot-flash-out');
                setTimeout(() => div.classList.remove('slot-flash-out'), 400);
            }
            div.className = 'buffer-slot slot-empty';
            div.innerHTML = '<span class="slot-item" style="font-size:.9rem;color:#4a5280">○</span>';
            prevBuffer[i] = false;
            return;
        }

        // Occupied slot
        if (!wasOccupied) {
            // Just added — flash green (producer inserted)
            div.classList.add('slot-flash-in');
            setTimeout(() => div.classList.remove('slot-flash-in'), 400);
        }

        // Age-based colour
        const ageMs   = order.createdAt ? now - new Date(order.createdAt).getTime() : 0;
        const ageSec  = ageMs / 1000;
        let ageClass  = ageSec < 10 ? 'slot-fresh' : ageSec < 20 ? 'slot-mid' : 'slot-stale';
        let premClass = order.priority === 'PREMIUM' ? ' slot-premium' : '';

        div.className = `buffer-slot ${ageClass}${premClass}`;
        div.innerHTML = `
            <span class="slot-id">#${order.id || '?'}</span>
            <span class="slot-item">${itemEmoji(order.item)}</span>
            <span class="slot-pri">${order.priority === 'PREMIUM' ? '⭐' : '·'}</span>
        `;
        prevBuffer[i] = true;
    });
}

function itemEmoji(item) {
    if (!item) return '📦';
    const map = {
        'Pizza': '🍕', 'Sushi': '🍣', 'Burger': '🍔',
        'Taco': '🌮', 'Ramen': '🍜', 'Chicken': '🍗',
        'Salad': '🥗', 'Ice Cream': '🍦'
    };
    for (const [k, v] of Object.entries(map)) {
        if (item.includes(k)) return v;
    }
    return '📦';
}

// ── Stats panel ──────────────────────────────────────────────────────────
function updateStats(state) {
    setText('stat-total',     state.totalOrders  || 0);
    setText('stat-delivered', state.delivered    || 0);
    setText('stat-cancelled', state.cancelled    || 0);
    setText('stat-queue',     state.inQueue      || 0);
    setText('stat-riders',    (state.riders || []).length);
    const avg = state.avgDeliveryTimeSeconds || 0;
    setText('stat-avgtime', avg.toFixed(1) + 's');
}

// ── Rider list ────────────────────────────────────────────────────────────
function updateRiderList(riders) {
    const container = document.getElementById('rider-list');
    container.innerHTML = '';
    riders.forEach(r => {
        const item = document.createElement('div');
        item.className = 'rider-item';
        item.innerHTML = `
            <span class="rider-icon">${riderIcon(r.status)}</span>
            <span class="rider-name">${r.name}</span>
            <span class="rider-status rs-${r.status}">${r.status}</span>
            <span class="rider-count">✅${r.deliveriesCompleted} ❌${r.deliveriesFailed}</span>
        `;
        container.appendChild(item);
    });
}

function riderIcon(status) {
    return { IDLE: '🏍️', DELIVERING: '🚀', TIRED: '😴', ACCIDENT: '💥' }[status] || '🏍️';
}

// ── Histogram ────────────────────────────────────────────────────────────
function drawHistogram(times) {
    const W = histCanvas.width, H = histCanvas.height;
    hctx.clearRect(0, 0, W, H);

    if (!times.length) return;

    const max = Math.max(...times, 1);
    const barW = W / times.length - 2;

    times.forEach((t, i) => {
        const barH = (t / max) * (H - 20);
        const x    = i * (barW + 2) + 1;
        const hue  = Math.max(0, 120 - (t / max) * 120); // green → red
        hctx.fillStyle = `hsl(${hue}, 80%, 50%)`;
        hctx.fillRect(x, H - barH - 16, barW, barH);
    });

    // x-axis label
    hctx.fillStyle = '#7a82a8';
    hctx.font = '9px sans-serif';
    hctx.textAlign = 'left';
    hctx.fillText('older', 2, H - 2);
    hctx.textAlign = 'right';
    hctx.fillText('latest', W - 2, H - 2);
}

// ── City canvas animation ────────────────────────────────────────────────
function scheduleFrame() {
    animFrameId = requestAnimationFrame(() => {
        animFrameId = null;
        drawCity();
        scheduleFrame(); // continuous animation for rain
    });
}

function drawCity() {
    const isRainy  = lastState && lastState.weather === 'RAINY';
    const riders   = lastState ? lastState.riders || [] : [];

    ctx.clearRect(0, 0, cityCanvas.width, cityCanvas.height);

    // Background
    ctx.fillStyle = isRainy ? '#0a0e1a' : '#111827';
    ctx.fillRect(0, 0, cityCanvas.width, cityCanvas.height);

    // Road grid
    ctx.strokeStyle = isRainy ? '#1e2d4a' : '#1f2937';
    ctx.lineWidth   = CELL_W * 0.55;
    for (let c = 0; c <= COLS; c++) {
        ctx.beginPath();
        ctx.moveTo(c * CELL_W, 0);
        ctx.lineTo(c * CELL_W, cityCanvas.height);
        ctx.stroke();
    }
    for (let r = 0; r <= ROWS; r++) {
        ctx.beginPath();
        ctx.moveTo(0, r * CELL_H);
        ctx.lineTo(cityCanvas.width, r * CELL_H);
        ctx.stroke();
    }

    // Block fills (buildings)
    ctx.fillStyle = isRainy ? '#0d131f' : '#161d2c';
    for (let c = 0; c < COLS; c++) {
        for (let r = 0; r < ROWS; r++) {
            const pad = 6;
            ctx.fillRect(
                c * CELL_W + pad, r * CELL_H + pad,
                CELL_W - pad * 2, CELL_H - pad * 2
            );
        }
    }

    // Rider targets (houses)
    riders.forEach(r => {
        if (r.status === 'DELIVERING' && r.targetPosition) {
            const tx = r.targetPosition.x * CELL_W + CELL_W / 2;
            const ty = r.targetPosition.y * CELL_H + CELL_H / 2;
            drawHouse(tx, ty, Date.now());
        }
    });

    // Riders
    riders.forEach(r => {
        if (!r.currentPosition) return;
        const rx = r.currentPosition.x * CELL_W + CELL_W / 2;
        const ry = r.currentPosition.y * CELL_H + CELL_H / 2;
        drawRider(rx, ry, r.status, r.id);
    });

    // Base marker
    drawBase(CELL_W / 2, CELL_H / 2);

    // Rain overlay
    if (isRainy) drawRain();
}

function drawHouse(x, y, t) {
    const blink = Math.sin(t / 400) > 0;
    ctx.save();
    ctx.translate(x, y);
    ctx.fillStyle = blink ? '#fbbf24' : '#92400e';
    ctx.beginPath();
    ctx.moveTo(0, -12);
    ctx.lineTo(-10, 2);
    ctx.lineTo(10, 2);
    ctx.closePath();
    ctx.fill();
    ctx.fillStyle = blink ? '#fde68a' : '#78350f';
    ctx.fillRect(-6, 2, 12, 10);
    ctx.restore();
}

function drawRider(x, y, status, id) {
    const colors = {
        IDLE:       '#22c55e',
        DELIVERING: '#3b82f6',
        TIRED:      '#eab308',
        ACCIDENT:   '#ef4444'
    };
    const col = colors[status] || '#8b8b8b';

    ctx.save();
    ctx.translate(x, y);

    if (status === 'ACCIDENT') {
        // Explosion effect
        const r = 8 + Math.sin(Date.now() / 100) * 4;
        ctx.beginPath();
        ctx.arc(0, 0, r, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(239,68,68,.4)';
        ctx.fill();
    }

    // Body circle
    ctx.beginPath();
    ctx.arc(0, 0, 9, 0, Math.PI * 2);
    ctx.fillStyle = col;
    ctx.fill();

    // Rider ID text
    ctx.fillStyle = '#000';
    ctx.font = 'bold 7px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(id || '?', 0, 0);

    ctx.restore();
}

function drawBase(x, y) {
    ctx.save();
    ctx.translate(x, y);
    ctx.fillStyle = '#7c3aed';
    ctx.fillRect(-12, -8, 24, 16);
    ctx.fillStyle = '#fff';
    ctx.font = 'bold 9px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('BASE', 0, 0);
    ctx.restore();
}

function drawRain() {
    ctx.strokeStyle = 'rgba(147,197,253,.25)';
    ctx.lineWidth = 1;
    DROPS.forEach(d => {
        ctx.beginPath();
        ctx.moveTo(d.x, d.y);
        ctx.lineTo(d.x - 4, d.y + 12);
        ctx.stroke();
        d.y += d.speed;
        d.x -= d.speed * 0.4;
        if (d.y > cityCanvas.height) {
            d.y = -10;
            d.x = Math.random() * cityCanvas.width;
        }
    });
}

// ── REST controls ─────────────────────────────────────────────────────────
async function simControl(action) {
    await fetch(`/api/simulation/${action}`, { method: 'POST' });
}

async function toggleWeather() {
    await fetch('/api/simulation/weather', { method: 'POST' });
}

async function addRider() {
    await fetch('/api/simulation/add-rider', { method: 'POST' });
}

// ── Utility ───────────────────────────────────────────────────────────────
function setText(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
}

// ── Initial draw ──────────────────────────────────────────────────────────
drawCity();
scheduleFrame();
