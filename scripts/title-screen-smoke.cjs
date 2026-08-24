const assert = require("node:assert/strict")
const fs = require("node:fs")
const path = require("node:path")
const { spawnSync } = require("node:child_process")
const { pathToFileURL } = require("node:url")

const nodeProcess = process
const SAVE_MARKER_KEY = "cyberslop.saved-game.available"
const bundlePath = path.resolve(nodeProcess.argv[2])
const savedGameFlag = nodeProcess.argv[3]

async function main() {
    if (savedGameFlag === undefined) {
        runIsolatedCase("without-save")
        runIsolatedCase("with-save")
        console.log("Production title-screen smoke tests passed.")
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
    const root = installBrowserEnvironment(hasSavedGame)

    await require(bundlePath)

    assert.equal(root.children[0]?.tagName, "H1")
    assert.equal(root.children[0]?.textContent, "Cyberslop")
    assert.deepEqual(
        root.children
            .filter((element) => element.tagName === "BUTTON")
            .map((button) => button.textContent),
        hasSavedGame ? ["Continue game", "New game"] : ["New game"],
    )
}

function installBrowserEnvironment(hasSavedGame) {
    const root = new FakeElement("main")
    const bundleUrl = pathToFileURL(bundlePath).href

    global.HTMLElement = FakeElement
    global.HTMLButtonElement = FakeButtonElement
    global.localStorage = fakeStorage(hasSavedGame)
    global.document = fakeDocument(root, bundleUrl)
    global.window = global
    global.self = global
    global.location = { href: bundleUrl }
    global.process = undefined
    global.fetch = loadWasmFile

    return root
}

function fakeStorage(hasSavedGame) {
    return {
        getItem(key) {
            return hasSavedGame && key === SAVE_MARKER_KEY ? "true" : null
        },
    }
}

function fakeDocument(root, bundleUrl) {
    return {
        baseURI: bundleUrl,
        currentScript: { tagName: "SCRIPT", src: bundleUrl },
        createElement(tagName) {
            return tagName === "button"
                ? new FakeButtonElement(tagName)
                : new FakeElement(tagName)
        },
        getElementById(id) {
            return id === "game-root" ? root : null
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
    }

    appendChild(child) {
        this.children.push(child)
        return child
    }
}

class FakeButtonElement extends FakeElement {}

main().catch(fail)
