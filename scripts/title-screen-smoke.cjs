const assert = require("node:assert/strict")
const fs = require("node:fs")
const path = require("node:path")
const { spawnSync } = require("node:child_process")
const { pathToFileURL } = require("node:url")

const nodeProcess = process
const RUN_KEY = "cyberslop.run.v1"

/**
 * One stroke setup per width-keyed batch; the start frame measured 14 for 41 segments before
 * drops were drawn in materials, and 34 after: every drop on the start screen may open a halo,
 * five material and two weathering-streak styles over its weights, plus its kind ring
 * (`specs/presentation.md`, Item icons). The bound is a constant over drops, not over segments.
 */
const MAX_STROKES_PER_FRAME = 48

/** A batched frame fills far more rectangles than it sets styles; measured well above this. */
const MIN_FILLS_PER_STYLE = 8

/** `strokeStyle`, `lineWidth`, `lineCap` — set once per stroke batch. */
const STROKE_PROPERTIES = 3

// A real encoded run, not a boolean marker. `Continue game` is offered only for a save that actually
// decodes, so a stale flag from an older deployment can no longer promise a run that is unreadable.
const VALID_SAVE = "2|12345|3|BrokenBottle||100.0|0|0"
const bundlePath = path.resolve(nodeProcess.argv[2])
const savedGameFlag = nodeProcess.argv[3]

async function main() {
    if (savedGameFlag === undefined) {
        runIsolatedCase("without-save")
        runIsolatedCase("with-save")
        runIsolatedCase("stale-marker")
        runIsolatedCase("starts-a-run")
        assertRelativeAssetPaths()
        console.log("Production smoke tests passed.")
        return
    }

    if (savedGameFlag === "starts-a-run") {
        await smokeTestStartingARun()
        return
    }
    if (savedGameFlag === "stale-marker") {
        await smokeTestStaleMarker()
        return
    }
    await smokeTestBundle(savedGameFlag === "with-save")
}

function runIsolatedCase(testCase) {
    const result = spawnSync(nodeProcess.execPath, [__filename, bundlePath, testCase], {
        encoding: "utf8",
    })

    if (result.status !== 0) {
        nodeProcess.stderr.write(result.stderr)
        nodeProcess.exit(result.status ?? 1)
    }
}

async function smokeTestBundle(hasSavedGame) {
    const env = installBrowserEnvironment(hasSavedGame)

    await require(bundlePath)

    assert.equal(env.root.children[0]?.tagName, "H1")
    assert.equal(env.root.children[0]?.textContent, "Cyberslop")
    assert.deepEqual(
        env.root.children
            .filter((element) => element.tagName === "BUTTON")
            .map((button) => button.textContent),
        hasSavedGame ? ["Continue game", "New game", "Shop"] : ["New game", "Shop"],
    )
}

/**
 * Booting without drawing is the failure an exception-only smoke test misses, so this asserts a
 * frame actually reached the canvas rather than merely that nothing threw.
 */
async function smokeTestStartingARun() {
    const env = installBrowserEnvironment(false)

    await require(bundlePath)

    const newGame = env.root.children.find(
        (element) => element.tagName === "BUTTON" && element.textContent === "New game",
    )
    assert.ok(newGame, "New game button missing")

    newGame.click()
    const frames = 3
    env.runFrames(frames)

    assert.equal(env.root.style.display, "none", "title screen still visible after starting")
    assert.equal(env.canvas.style.display, "block", "canvas not shown after starting")
    assert.ok(env.context.fillRectCalls > 0, "a run started but no frame was drawn")
    assert.ok(
        env.context.strokeCalls > 0,
        "a run started but nothing was stroked, so no figure was drawn",
    )
    assert.ok(
        env.context.fillTextCalls > 0,
        "a run started but no HUD text was drawn",
    )

    // ENG-061, against the production bundle: one stroke setup per width-keyed batch, however many
    // segments are in it. Round one found the renderer breaking its path *inside* a batch, at 1,579
    // stroke setups for 600 entities while every other check stayed green.
    //
    // The comparison is against the number of segments drawn, not a fixed ceiling. Round three
    // showed why: the start frame holds 14 batches but only 41 segments, so a fixed bound of 60
    // would have let the regression through at 41 strokes and still passed. Stroking per segment
    // makes these two numbers equal, whatever the frame contains.
    const strokes = env.context.strokeCalls
    const segments = env.context.lineToCalls
    assert.ok(segments > 0, "no segments were drawn, so the stroke bound proves nothing")
    assert.ok(
        strokes * 2 <= segments,
        `${strokes} stroke setups for ${segments} segments — the renderer is breaking its ` +
            "stroke path inside a batch rather than once per batch",
    )
    assert.ok(
        strokes / frames <= MAX_STROKES_PER_FRAME,
        `${strokes / frames} strokes per frame, over the ${MAX_STROKES_PER_FRAME} a batched ` +
            "frame costs",
    )

    // The same argument for fills. A frame draws thousands of tile rectangles from a handful of
    // style batches, so reconfiguring the canvas per rectangle would collapse this ratio.
    const fills = env.context.fillRectCalls
    const styleChanges = env.context.fillStyleAssignments
    assert.ok(
        styleChanges * MIN_FILLS_PER_STYLE <= fills,
        `${styleChanges} fill-style assignments for ${fills} rectangles — the canvas is being ` +
            "reconfigured inside a batch rather than once per batch",
    )

    // A stroke batch configures three properties once. Per segment, this becomes three per segment.
    const strokeConfig = env.context.strokeConfigAssignments
    assert.ok(
        strokeConfig <= strokes * STROKE_PROPERTIES,
        `${strokeConfig} stroke-property assignments for ${strokes} strokes — more than the ` +
            `${STROKE_PROPERTIES} a batch sets once, so one of them is inside the segment loop`,
    )
}

/**
 * An unreadable save must not offer `Continue game`. TITLE-003 asks for a *valid* previous save, and
 * a save written by an older deployment is exactly the case a boolean flag gets wrong.
 */
async function smokeTestStaleMarker() {
    const env = installBrowserEnvironment(false)
    global.localStorage.setItem(RUN_KEY, "99|not-a-save-this-build-understands")

    await require(bundlePath)

    assert.deepEqual(
        env.root.children
            .filter((element) => element.tagName === "BUTTON")
            .map((button) => button.textContent),
        ["New game", "Shop"],
        "an unreadable save was offered as resumable",
    )
}

function assertRelativeAssetPaths() {
    // GitHub Pages serves this project under /cyberslop/, so a root-relative URL works locally and
    // 404s once deployed.
    const indexPath = path.join(path.dirname(bundlePath), "index.html")
    const markup = fs.readFileSync(indexPath, "utf8")
    const rootRelative = markup.match(/(?:src|href)="\/[^"]*"/g)
    assert.equal(rootRelative, null, `root-relative asset paths would 404 on Pages: ${rootRelative}`)
}

function installBrowserEnvironment(hasSavedGame) {
    const root = new FakeElement("main")
    const canvas = new FakeCanvasElement("canvas")
    const bundleUrl = pathToFileURL(bundlePath).href
    const frameCallbacks = []

    global.HTMLElement = FakeElement
    global.HTMLButtonElement = FakeButtonElement
    global.HTMLCanvasElement = FakeCanvasElement
    // The bundle type-checks event arguments, so these must exist as constructors even though the
    // smoke test never dispatches a real event.
    global.Event = FakeEvent
    global.UIEvent = FakeEvent
    global.MouseEvent = FakeMouseEvent
    global.KeyboardEvent = FakeKeyboardEvent
    global.FocusEvent = FakeEvent
    global.CanvasRenderingContext2D = FakeContext2D
    global.localStorage = fakeStorage(hasSavedGame)
    global.document = fakeDocument(root, canvas, bundleUrl)
    global.window = global
    // Input wiring listens on the window; the smoke test never dispatches a key.
    global.addEventListener = () => {}
    global.removeEventListener = () => {}
    global.self = global
    global.location = { href: bundleUrl }
    global.process = undefined
    global.fetch = loadWasmFile
    global.performance = { now: () => frameCallbacks.length * 16.7 + 1 }
    global.requestAnimationFrame = (callback) => frameCallbacks.push(callback)
    global.cancelAnimationFrame = () => {}

    return {
        root,
        canvas,
        context: canvas.context,
        runFrames(count) {
            for (let i = 0; i < count; i++) {
                const next = frameCallbacks.shift()
                if (!next) return
                next(global.performance.now())
            }
        },
    }
}

function fakeStorage(hasSavedGame) {
    const values = new Map()
    if (hasSavedGame) values.set(RUN_KEY, VALID_SAVE)
    return {
        getItem: (key) => (values.has(key) ? values.get(key) : null),
        setItem: (key, value) => values.set(key, String(value)),
        removeItem: (key) => values.delete(key),
    }
}

function fakeDocument(root, canvas, bundleUrl) {
    return {
        baseURI: bundleUrl,
        currentScript: { tagName: "SCRIPT", src: bundleUrl },
        createElement(tagName) {
            if (tagName === "button") return new FakeButtonElement(tagName)
            if (tagName === "canvas") return new FakeCanvasElement(tagName)
            return new FakeElement(tagName)
        },
        getElementById(id) {
            if (id === "game-root") return root
            if (id === "game-canvas") return canvas
            return null
        },
        getElementsByTagName() {
            return []
        },
        addEventListener() {},
        removeEventListener() {},
        hasFocus: () => true,
        hidden: false,
    }
}

async function loadWasmFile(url) {
    return new Response(fs.readFileSync(new URL(url)), {
        headers: { "content-type": "application/wasm" },
    })
}

function fail(error) {
    nodeProcess.stderr.write(`${error.stack}\n`)
    nodeProcess.exitCode = 1
}

class FakeElement {
    constructor(tagName) {
        this.tagName = tagName.toUpperCase()
        this.children = []
        this.textContent = ""
        this.style = {}
        this.onclick = null
        this.attributes = new Map()
    }

    appendChild(child) {
        this.children.push(child)
        return child
    }

    click() {
        // The bundle casts the handler argument, so this must be a real MouseEvent instance.
        if (this.onclick) this.onclick(new FakeMouseEvent("click"))
    }

    focus() {}

    addEventListener() {}

    removeEventListener() {}

    setAttribute(name, value) {
        this.attributes.set(name, String(value))
    }

    getAttribute(name) {
        return this.attributes.has(name) ? this.attributes.get(name) : null
    }
}

class FakeButtonElement extends FakeElement {}

class FakeEvent {
    constructor(type) {
        this.type = type
    }

    preventDefault() {}
}

class FakeMouseEvent extends FakeEvent {
    constructor(type) {
        super(type)
        this.clientX = 0
        this.clientY = 0
    }
}

class FakeKeyboardEvent extends FakeEvent {
    constructor(type) {
        super(type)
        this.code = ""
        this.key = ""
    }
}

class FakeCanvasElement extends FakeElement {
    constructor(tagName) {
        super(tagName)
        this.width = 960
        this.height = 540
        this.clientWidth = 960
        this.clientHeight = 540
        this.tabIndex = 0
        this.context = new FakeContext2D()
    }

    getContext(kind) {
        return kind === "2d" ? this.context : null
    }

    getBoundingClientRect() {
        return { left: 0, top: 0, width: this.width, height: this.height }
    }
}

// Everything the renderer actually calls. The stub used to carry `fillRect` alone, which was
// enough while every sprite was a rectangle; the first stroked limb made the production bundle
// throw here, which is exactly what this smoke test exists to catch.
class FakeContext2D {
    constructor() {
        this._fillStyle = null
        this._strokeStyle = null
        this._lineWidth = 1
        this._lineCap = "butt"
        this.fillStyleAssignments = 0
        // Every property a stroke configures, so moving any one of them into the segment loop is
        // visible. Counting only `stroke()` left two of the three unguarded.
        this.strokeConfigAssignments = 0
        this.font = ""
        this.textAlign = "left"
        this.imageSmoothingEnabled = true
        this.fillRectCalls = 0
        this.strokeCalls = 0
        this.lineToCalls = 0
        this.fillTextCalls = 0
    }

    // A counted property, so the smoke test can see how often the canvas is *reconfigured* rather
    // than only how often it is drawn to. Round seven pointed out that moving a fill-style
    // assignment into a loop would leave every other check green.
    get fillStyle() {
        return this._fillStyle
    }

    set fillStyle(value) {
        this._fillStyle = value
        this.fillStyleAssignments++
    }

    get strokeStyle() {
        return this._strokeStyle
    }

    set strokeStyle(value) {
        this._strokeStyle = value
        this.strokeConfigAssignments++
    }

    get lineWidth() {
        return this._lineWidth
    }

    set lineWidth(value) {
        this._lineWidth = value
        this.strokeConfigAssignments++
    }

    get lineCap() {
        return this._lineCap
    }

    set lineCap(value) {
        this._lineCap = value
        this.strokeConfigAssignments++
    }

    fillRect() {
        this.fillRectCalls++
    }

    beginPath() {}

    moveTo() {}

    lineTo() {
        this.lineToCalls++
    }

    arc() {}

    stroke() {
        this.strokeCalls++
    }

    fill() {}

    fillText() {
        this.fillTextCalls++
    }

    drawImage() {}
}

main().catch(fail)
