const assert = require("node:assert/strict")
const fs = require("node:fs")
const path = require("node:path")
const { spawnSync } = require("node:child_process")
const { pathToFileURL } = require("node:url")

const nodeProcess = process
const RUN_KEY = "cyberslop.run.v1"

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
        hasSavedGame ? ["Continue game", "New game"] : ["New game"],
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
    env.runFrames(3)

    assert.equal(env.root.style.display, "none", "title screen still visible after starting")
    assert.equal(env.canvas.style.display, "block", "canvas not shown after starting")
    assert.ok(env.context.fillRectCalls > 0, "a run started but no frame was drawn")
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
        ["New game"],
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

class FakeContext2D {
    constructor() {
        this.fillStyle = null
        this.imageSmoothingEnabled = true
        this.fillRectCalls = 0
    }

    fillRect() {
        this.fillRectCalls++
    }

    drawImage() {}
}

main().catch(fail)
