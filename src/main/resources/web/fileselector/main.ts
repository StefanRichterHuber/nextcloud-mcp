import bootstrap from 'bootstrap/dist/js/bootstrap.bundle.js'
import { App, McpUiHostContext, applyDocumentTheme, applyHostStyleVariables, applyHostFonts } from "@modelcontextprotocol/ext-apps";

interface FolderEntry {
    name: string;
    path: string;
    directory: boolean;
    size?: number | null;
    modified?: number | null;
    contentType?: string | null;
}

interface FolderListing {
    path: string;
    parentPath: string | null;
    entries: FolderEntry[];
}

interface AddedResource {
    path: string;
    resourceUri: string;
}

interface AddResourcesResult {
    added: AddedResource[];
    failed: Record<string, string>;
}

const fileListEl = document.getElementById("fileList") as HTMLDivElement;
const breadcrumbsEl = document.getElementById("breadcrumbs") as HTMLElement;
const upBtn = document.getElementById("upBtn") as HTMLButtonElement;
const clearSelectionBtn = document.getElementById("clearSelectionBtn") as HTMLButtonElement;
const selectedCountEl = document.getElementById("selectedCount") as HTMLElement;
const addResourcesBtn = document.getElementById("addResourcesBtn") as HTMLButtonElement;

const toastSuccessEl = document.getElementById("toast-success") as HTMLElement;
const toastSuccessTextEl = document.getElementById("toast-success-text") as HTMLElement;
const toastErrorEl = document.getElementById("toast-error") as HTMLElement;
const toastErrorTextEl = document.getElementById("toast-error-text") as HTMLElement;

const selection = new Set<string>();
let currentListing: FolderListing | null = null;

function showToast(el: HTMLElement, textEl: HTMLElement, message: string) {
    textEl.textContent = message;
    const toast = bootstrap.Toast.getOrCreateInstance(el);
    toast.show();
}

function formatSize(size?: number | null): string {
    if (size === null || size === undefined) return "";
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function updateSelectedCount() {
    selectedCountEl.textContent = String(selection.size);
    addResourcesBtn.disabled = selection.size === 0;
}

function renderBreadcrumbs(listing: FolderListing) {
    breadcrumbsEl.innerHTML = "";
    const segments = listing.path.split("/").filter(Boolean);

    const rootBtn = document.createElement("button");
    rootBtn.type = "button";
    rootBtn.innerHTML = '<i class="bi bi-house"></i>';
    if (segments.length === 0) {
        rootBtn.disabled = true;
    } else {
        rootBtn.addEventListener("click", () => navigate("/"));
    }
    breadcrumbsEl.appendChild(rootBtn);

    let accPath = "";
    segments.forEach((segment, index) => {
        accPath += "/" + segment;
        const sep = document.createElement("span");
        sep.className = "sep";
        sep.textContent = "/";
        breadcrumbsEl.appendChild(sep);

        const btn = document.createElement("button");
        btn.type = "button";
        btn.textContent = segment;
        const targetPath = accPath;
        if (index === segments.length - 1) {
            btn.disabled = true;
        } else {
            btn.addEventListener("click", () => navigate(targetPath));
        }
        breadcrumbsEl.appendChild(btn);
    });
}

function renderEntries(listing: FolderListing) {
    fileListEl.innerHTML = "";

    if (listing.entries.length === 0) {
        fileListEl.innerHTML = `<div class="empty-state"><i class="bi bi-folder2-open"></i><span>This folder is empty.</span></div>`;
        return;
    }

    listing.entries.forEach((entry) => {
        const row = document.createElement("div");
        row.className = "file-row" + (entry.directory ? " folder" : "");
        if (!entry.directory && selection.has(entry.path)) {
            row.classList.add("selected");
        }

        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        if (entry.directory) {
            checkbox.disabled = true;
            checkbox.title = "Folders cannot be added as resources directly";
        } else {
            checkbox.checked = selection.has(entry.path);
            checkbox.addEventListener("click", (e) => e.stopPropagation());
            checkbox.addEventListener("change", () => toggleSelection(entry.path, row, checkbox.checked));
        }
        row.appendChild(checkbox);

        const icon = document.createElement("i");
        icon.className = `bi ${entry.directory ? "bi-folder2" : "bi-file-earmark"} row-icon`;
        row.appendChild(icon);

        const name = document.createElement("span");
        name.className = "row-name";
        name.textContent = entry.name;
        row.appendChild(name);

        if (!entry.directory) {
            const meta = document.createElement("span");
            meta.className = "row-meta";
            meta.textContent = formatSize(entry.size);
            row.appendChild(meta);
        } else {
            const nav = document.createElement("span");
            nav.className = "row-nav";
            nav.innerHTML = '<i class="bi bi-chevron-right"></i>';
            row.appendChild(nav);
        }

        row.addEventListener("click", () => {
            if (entry.directory) {
                navigate(entry.path);
            } else {
                checkbox.checked = !checkbox.checked;
                toggleSelection(entry.path, row, checkbox.checked);
            }
        });

        fileListEl.appendChild(row);
    });
}

function toggleSelection(path: string, row: HTMLElement, selected: boolean) {
    if (selected) {
        selection.add(path);
        row.classList.add("selected");
    } else {
        selection.delete(path);
        row.classList.remove("selected");
    }
    updateSelectedCount();
}

function renderLoading() {
    fileListEl.innerHTML = `<div class="loading-state"><div class="spinner"></div><span>Loading folder…</span></div>`;
}

function renderError(message: string) {
    fileListEl.innerHTML = `<div class="error-state"><i class="bi bi-exclamation-triangle"></i><span>${message}</span></div>`;
}

function render(listing: FolderListing) {
    currentListing = listing;
    upBtn.disabled = !listing.parentPath;
    renderBreadcrumbs(listing);
    renderEntries(listing);
}

async function navigate(path: string) {
    renderLoading();
    try {
        const result = await app.callServerTool({
            name: "file-selector-list-folder",
            arguments: { path },
        });
        if (result.isError) {
            renderError("Could not load this folder.");
            return;
        }
        render(result.structuredContent as unknown as FolderListing);
    } catch (e) {
        renderError("Could not load this folder.");
    }
}

upBtn.addEventListener("click", () => {
    if (currentListing?.parentPath) {
        navigate(currentListing.parentPath);
    }
});

clearSelectionBtn.addEventListener("click", () => {
    selection.clear();
    if (currentListing) {
        renderEntries(currentListing);
    }
    updateSelectedCount();
});

addResourcesBtn.addEventListener("click", async () => {
    if (selection.size === 0) return;
    addResourcesBtn.disabled = true;
    try {
        const result = await app.callServerTool({
            name: "file-selector-add-resources",
            arguments: { paths: [...selection] },
        });
        if (result.isError) {
            showToast(toastErrorEl, toastErrorTextEl, "Failed to add files as resources.");
            return;
        }
        const data = result.structuredContent as unknown as AddResourcesResult;
        const failedPaths = Object.keys(data.failed ?? {});
        data.added.forEach((a) => selection.delete(a.path));
        updateSelectedCount();
        if (currentListing) {
            renderEntries(currentListing);
        }

        if (data.added.length > 0) {
            showToast(
                toastSuccessEl,
                toastSuccessTextEl,
                `Added ${data.added.length} file(s) as resource${data.added.length === 1 ? "" : "s"}.`,
            );
        }
        if (failedPaths.length > 0) {
            showToast(
                toastErrorEl,
                toastErrorTextEl,
                `Failed to add ${failedPaths.length} file(s): ${failedPaths.join(", ")}`,
            );
        }
    } catch (e) {
        showToast(toastErrorEl, toastErrorTextEl, "Failed to add files as resources.");
    } finally {
        addResourcesBtn.disabled = selection.size === 0;
    }
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

const app = new App({ name: "Select Nextcloud Files", version: "1.0.0" });
app.onerror = console.error;
// Handle the initial tool result pushed by the host
app.ontoolresult = (result) => {
    if (result.isError) {
        renderError("Could not load your files.");
        return;
    }
    render(result.structuredContent as unknown as FolderListing);
};

app.onhostcontextchanged = handleHostContextChanged;
// Establish communication with the host
app.connect().then(() => {
    const ctx = app.getHostContext();
    if (ctx) {
        handleHostContextChanged(ctx);
    }
});
