import bootstrap from 'bootstrap/dist/js/bootstrap.bundle.js'
import { App, McpUiHostContext, applyDocumentTheme, applyHostStyleVariables, applyHostFonts } from "@modelcontextprotocol/ext-apps";
/* ── Pattern tag management ── */
const patterns = new Set();
const tagsEl = document.getElementById("pattern-tags");
const patternIn = document.getElementById("patternInput") as HTMLInputElement;
const hiddenPat = document.getElementById("filePatterns") as HTMLInputElement;

const VALID_PATTERN = /^(\*\.[a-zA-Z0-9]+|\*|[a-zA-Z0-9_\-\.]+(\*?))$/;

function renderTags() {
    tagsEl.innerHTML = "";
    patterns.forEach((p) => {
        const pill = document.createElement("span");
        pill.className = "tag-pill";
        pill.innerHTML = `${p}<button type="button" title="Remove">&times;</button>`;
        pill.querySelector("button").addEventListener("click", () => {
            patterns.delete(p);
            renderTags();
            syncHidden();
        });
        tagsEl.appendChild(pill);
    });
}

function syncHidden() {
    hiddenPat.value = [...patterns].join(",");
}

function addPatterns(raw) {
    raw
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean)
        .forEach((p) => {
            if (VALID_PATTERN.test(p)) patterns.add(p);
        });
    renderTags();
    syncHidden();
}

patternIn.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === ",") {
        e.preventDefault();
        addPatterns(patternIn.value);
        patternIn.value = "";
    }
});

patternIn.addEventListener("blur", () => {
    if (patternIn.value.trim()) {
        addPatterns(patternIn.value);
        patternIn.value = "";
    }
});

/* ── Form validation & submission ── */
const form = document.getElementById("scanForm") as HTMLFormElement;
const rootInput = document.getElementById("rootFolder") as HTMLInputElement;
const checkboxes = document.querySelectorAll(".content-check") as NodeListOf<HTMLInputElement>;
const contentErr = document.getElementById("content-error");

function validateContentTypes() {
    const checked = [...checkboxes].some((c) => c.checked);
    contentErr.classList.toggle("show", !checked);
    checkboxes.forEach((c) => c.classList.toggle("is-invalid", !checked));
    return checked;
}

function validatePatterns() {
    const ok = patterns.size > 0;
    const inp = document.getElementById("patternInput");
    hiddenPat.classList.toggle("is-invalid", !ok);
    inp.classList.toggle("is-invalid", !ok);
    document.getElementById("pattern-error").style.display = ok
        ? "none"
        : "block";
    return ok;
}

checkboxes.forEach((c) =>
    c.addEventListener("change", validateContentTypes),
);

form.addEventListener("submit", async (e) => {
    e.preventDefault();
    e.stopPropagation();

    form.classList.add("was-validated");
    const rootOk = rootInput.checkValidity();
    const patOk = validatePatterns();
    const contentOk = validateContentTypes();

    if (!rootOk || !patOk || !contentOk) return;

    // Sync hidden pattern field before building FormData
    syncHidden();

    const formData = new FormData(form);

    var object = {};
    formData.forEach((value, key) => object[key] = value);
    var json = JSON.stringify(object);

    const result = await app.callServerTool({
        name: "set-access-config",
        arguments: { config: json },
    });
});

function handleHostContextChanged(ctx: McpUiHostContext) {
    if (ctx.theme) {
        applyDocumentTheme(ctx.theme);
    }
    if (ctx.styles?.variables) {
        applyHostStyleVariables(ctx.styles.variables);
    }
    if (ctx.styles?.css?.fonts) {
        applyHostFonts(ctx.styles.css.fonts);
    }
    if (ctx.safeAreaInsets) {
        const root = document.documentElement;
        root.style.setProperty("--safe-area-top", `${ctx.safeAreaInsets.top}px`);
        root.style.setProperty("--safe-area-right", `${ctx.safeAreaInsets.right}px`);
        root.style.setProperty("--safe-area-bottom", `${ctx.safeAreaInsets.bottom}px`);
        root.style.setProperty("--safe-area-left", `${ctx.safeAreaInsets.left}px`);
    }
}

interface ConfigFromServer {
    rootFolder: string,
    filePatterns: string[],
    textContent: boolean,
    imageContent: boolean,
    audioContent: boolean
}

const app = new App({ name: "Configure Nextcloud MCP App", version: "1.0.0" });
app.onerror = console.error;
// Handle the initial tool result pushed by the host
app.ontoolresult = (result) => {
    const configText = result.content?.find((c) => c.type === "text")?.text;
    const config = JSON.parse(configText) as ConfigFromServer;
    if (config.rootFolder) {
        rootInput.value = config.rootFolder;
    }
    if (config.audioContent) {
        (document.getElementById("chkAudio") as HTMLInputElement).click();
    }
    if (config.imageContent) {
        (document.getElementById("chkImage") as HTMLInputElement).click();
    }
    if (config.textContent) {
        (document.getElementById("chkText") as HTMLInputElement).click();
    }
    if (config.filePatterns) {
        config.filePatterns.forEach(v => patterns.add(v));
        renderTags();
    }

};

app.onhostcontextchanged = handleHostContextChanged;
// Establish communication with the host
// 3. Connect to host
app.connect().then(() => {
    const ctx = app.getHostContext();
    if (ctx) {
        handleHostContextChanged(ctx);
    }
});
