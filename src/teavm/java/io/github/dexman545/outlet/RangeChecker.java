package io.github.dexman545.outlet;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.html.HTMLInputElement;

import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

public class RangeChecker {
    private static List<McEntry> allVersions = new ArrayList<>();
    private static List<McEntry> modrinthVersions = new ArrayList<>();
    private static final List<HTMLInputElement> inputs = new ArrayList<>();
    private static final List<HTMLInputElement> customVersionInputs = new ArrayList<>();
    private static int nextInputId = 0;
    private static Mode currentMode = Mode.MINECRAFT;

    private enum Mode { MINECRAFT, MODRINTH, CUSTOM }

    public static void main(String[] args) {
        var xhr = XMLHttpRequest.create();
        xhr.open("GET", "https://dexman545.github.io/outlet-database/mc2fabric.json");
        xhr.addEventListener("readystatechange", e -> {
            if (xhr.getReadyState() == XMLHttpRequest.DONE) {
                if (xhr.getStatus() >= 200 && xhr.getStatus() < 300) {
                    try {
                        allVersions = readVersions(xhr.getResponseText());
                        //Collections.sort(allVersions);
                        allVersions.sort(Comparator.reverseOrder());
                        setupPage();
                    } catch (RuntimeException ex) {
                        showStartupError("Failed to parse version database: " + ex.getMessage());
                    }
                } else {
                    showStartupError("Failed to load version database: HTTP " + xhr.getStatus());
                }
            }
        });
        xhr.send();
    }

    private static void showStartupError(String message) {
        var document = HTMLDocument.current();
        var errorEl = document.getElementById("error-message");
        errorEl.getStyle().setProperty("display", "block");
        errorEl.setInnerHTML(escapeHtml(message));
    }

    private static void setupPage() {
        var document = HTMLDocument.current();
        var predicateContainer = document.getElementById("predicate-inputs");
        var customContainer = document.getElementById("custom-version-inputs");
        var errorEl = document.getElementById("error-message");
        var countEl = document.getElementById("match-count");

        renderList(allVersions, countEl);

        List<String> params = readPredicateParams();
        for (String param : params) {
            addInputBox(document, predicateContainer, inputs, () -> refresh(errorEl, countEl), "e.g. >=1.20.0 <1.21");
            inputs.get(inputs.size() - 1).setValue(param);
        }
        if (!params.isEmpty()) {
            filterVersions(errorEl, countEl);
        }
        addInputBox(document, predicateContainer, inputs, () -> refresh(errorEl, countEl), "e.g. >=1.20.0 <1.21");

        addInputBox(document, customContainer, customVersionInputs, () -> refresh(errorEl, countEl), "e.g. 1.20.1");

        setupModeRadios(document, errorEl, countEl);
        setupModrinthPanel(document, errorEl, countEl);

        // Apply URL params (mode, modrinth slug, custom versions)
        applyUrlParams(document, errorEl, countEl);

        var copyBtn = document.getElementById("copy-url-btn");
        copyBtn.addEventListener("click", e -> {
            copyToClipboard(buildShareUrl());
            copyBtn.setInnerHTML("Copied!");
            resetAfterDelay(copyBtn, "Copy URL", 2000);
        });
    }

    private static void setupModeRadios(HTMLDocument document, HTMLElement errorEl, HTMLElement countEl) {
        var mcRadio = document.getElementById("mode-minecraft");
        var mrRadio = document.getElementById("mode-modrinth");
        var customRadio = document.getElementById("mode-custom");
        var predicatePanel = document.getElementById("predicate-panel");
        var modrinthPanel = document.getElementById("modrinth-panel");
        var customPanel = document.getElementById("custom-panel");

        mcRadio.addEventListener("change", e -> {
            currentMode = Mode.MINECRAFT;
            predicatePanel.getStyle().setProperty("display", "block");
            modrinthPanel.getStyle().setProperty("display", "none");
            customPanel.getStyle().setProperty("display", "none");
            refresh(errorEl, countEl);
        });

        mrRadio.addEventListener("change", e -> {
            currentMode = Mode.MODRINTH;
            // show predicates for modrinth too so users can filter fetched versions
            predicatePanel.getStyle().setProperty("display", "block");
            modrinthPanel.getStyle().setProperty("display", "block");
            customPanel.getStyle().setProperty("display", "none");
            errorEl.getStyle().setProperty("display", "none");
            // apply predicates when showing modrinth list
            filterModrinthVersions(errorEl, countEl);
        });

        customRadio.addEventListener("change", e -> {
            currentMode = Mode.CUSTOM;
            predicatePanel.getStyle().setProperty("display", "block");
            modrinthPanel.getStyle().setProperty("display", "none");
            customPanel.getStyle().setProperty("display", "block");
            refresh(errorEl, countEl);
        });
    }

    private static void setupModrinthPanel(HTMLDocument document, HTMLElement errorEl, HTMLElement countEl) {
        var slugInput = (HTMLInputElement) document.getElementById("modrinth-slug-input");
        var fetchBtn = document.getElementById("modrinth-fetch-btn");

        fetchBtn.addEventListener("click", e -> {
            String slug = slugInput.getValue();
            if (slug == null || slug.isBlank()) return;
            fetchModrinthVersions(slug.trim(), errorEl, countEl, fetchBtn);
        });

        // allow pressing Enter in the slug input to trigger fetch
        addEnterKeyHandler(slugInput, fetchBtn);
    }

    private static void fetchModrinthVersions(String slug, HTMLElement errorEl, HTMLElement countEl, HTMLElement fetchBtn) {
        errorEl.getStyle().setProperty("display", "none");
        fetchBtn.setInnerHTML("Fetching...");

        var xhr = XMLHttpRequest.create();
        String url = "https://api.modrinth.com/v2/project/" + encodeURIComponent(slug) + "/version?loaders=[\"fabric\"]";
        xhr.open("GET", url);
        xhr.addEventListener("readystatechange", e -> {
            if (xhr.getReadyState() == XMLHttpRequest.DONE) {
                fetchBtn.setInnerHTML("Fetch Versions");
                if (xhr.getStatus() >= 200 && xhr.getStatus() < 300) {
                    try {
                        modrinthVersions = readModrinthVersions(xhr.getResponseText());
                        errorEl.getStyle().setProperty("display", "none");
                        if (currentMode == Mode.MODRINTH) {
                            filterModrinthVersions(errorEl, countEl);
                        }
                    } catch (RuntimeException ex) {
                        errorEl.getStyle().setProperty("display", "block");
                        errorEl.setInnerHTML(escapeHtml("Failed to parse Modrinth response: " + ex.getMessage()));
                    }
                } else {
                    errorEl.getStyle().setProperty("display", "block");
                    errorEl.setInnerHTML(escapeHtml("Failed to load Modrinth versions: HTTP " + xhr.getStatus()));
                }
            }
        });
        xhr.send();
    }

    private static List<String> readPredicateParams() {
        String search = getLocationSearch();
        List<String> result = new ArrayList<>();
        if (search == null || search.length() <= 1) return result;
        for (String part : search.substring(1).split("&")) {
            if (part.startsWith("p=")) {
                result.add(decodeURIComponent(part.substring(2).replace("+", " ")));
            }
        }
        return result;
    }

    private static List<String> readCustomParams() {
        String search = getLocationSearch();
        List<String> result = new ArrayList<>();
        if (search == null || search.length() <= 1) return result;
        for (String part : search.substring(1).split("&")) {
            if (part.startsWith("c=")) {
                result.add(decodeURIComponent(part.substring(2).replace("+", " ")));
            }
        }
        return result;
    }

    private static String readModeParam() {
        String search = getLocationSearch();
        if (search == null || search.length() <= 1) return null;
        for (String part : search.substring(1).split("&")) {
            if (part.startsWith("mode=")) {
                return decodeURIComponent(part.substring(5));
            }
        }
        return null;
    }

    private static String readModrinthParam() {
        String search = getLocationSearch();
        if (search == null || search.length() <= 1) return null;
        for (String part : search.substring(1).split("&")) {
            if (part.startsWith("modrinth=")) {
                return decodeURIComponent(part.substring(9));
            }
        }
        return null;
    }

    private static String buildShareUrl() {
        List<String> params = new ArrayList<>();

        for (var input : inputs) {
            String v = input.getValue();
            if (v != null && !v.isBlank()) {
                params.add("p=" + encodeURIComponent(v));
            }
        }

        // include mode
        params.add("mode=" + currentMode.name().toLowerCase());

        // include modrinth slug when applicable
        if (currentMode == Mode.MODRINTH) {
            var document = HTMLDocument.current();
            var slugInput = (HTMLInputElement) document.getElementById("modrinth-slug-input");
            String slug = slugInput.getValue();
            if (slug != null && !slug.isBlank()) {
                params.add("modrinth=" + encodeURIComponent(slug.trim()));
            }
        }

        // include custom versions when applicable
        if (currentMode == Mode.CUSTOM) {
            for (var c : customVersionInputs) {
                String v = c.getValue();
                if (v != null && !v.isBlank()) {
                    params.add("c=" + encodeURIComponent(v));
                }
            }
        }

        if (params.isEmpty()) return getBaseUrl();

        StringBuilder sb = new StringBuilder(getBaseUrl()).append("?");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append("&");
            sb.append(params.get(i));
        }
        return sb.toString();
    }

    /**
     * Generic auto-growing list of text inputs: whenever the last input in {@code list} gets a
     * non-blank value, a new empty input is appended. Blurring an empty, non-last input removes it.
     */
    private static void addInputBox(HTMLDocument document, HTMLElement container, List<HTMLInputElement> list,
                                     Runnable onChange, String placeholder) {
        var input = (HTMLInputElement) document.createElement("input");
        input.setAttribute("type", "text");
        input.setAttribute("class", "predicate-input");
        input.setAttribute("placeholder", placeholder);
        input.setAttribute("autocomplete", "off");
        input.setAttribute("spellcheck", "false");
        input.setAttribute("data-pi-id", String.valueOf(nextInputId++));

        list.add(input);
        container.appendChild(input);

        input.addEventListener("input", e -> {
            String lastValue = list.get(list.size() - 1).getValue();
            if (lastValue != null && !lastValue.isBlank()) {
                addInputBox(document, container, list, onChange, placeholder);
            }
            onChange.run();
        });

        input.addEventListener("blur", e -> {
            String value = input.getValue();
            if (value != null && !value.isBlank()) return;
            String myId = input.getAttribute("data-pi-id");
            String lastId = list.get(list.size() - 1).getAttribute("data-pi-id");
            if (myId.equals(lastId)) return; // last input, keep it
            for (int i = 0; i < list.size(); i++) {
                if (myId.equals(list.get(i).getAttribute("data-pi-id"))) {
                    list.remove(i);
                    container.removeChild(input);
                    onChange.run();
                    break;
                }
            }
        });
    }

    private static void refresh(HTMLElement errorEl, HTMLElement countEl) {
        switch (currentMode) {
            case MINECRAFT -> filterVersions(errorEl, countEl);
            case MODRINTH -> filterModrinthVersions(errorEl, countEl);
            case CUSTOM -> renderCustomVersions(errorEl, countEl);
        }
    }

    private static void filterVersions(HTMLElement errorEl, HTMLElement countEl) {
        List<String> values = new ArrayList<>();
        for (var input : inputs) {
            String v = input.getValue();
            if (v != null && !v.isBlank()) {
                values.add(v);
            }
        }

        if (values.isEmpty()) {
            errorEl.getStyle().setProperty("display", "none");
            renderList(allVersions, countEl);
            return;
        }

        try {
            Collection<VersionPredicate> predicates = createPredicates(values);
            errorEl.getStyle().setProperty("display", "none");
            var filtered = new ArrayList<McEntry>();
            outer:
            for (var entry : allVersions) {
                for (var predicate : predicates) {
                    if (predicate.test(entry.semver())) {
                        filtered.add(entry);
                        continue outer;
                    }
                }
            }
            renderList(filtered, countEl);
        } catch (VersionParsingException ex) {
            errorEl.getStyle().setProperty("display", "block");
            errorEl.setInnerHTML(escapeHtml("Invalid predicate: " + ex.getMessage()));
        } catch (AssertionError ex) {
            var sw = new StringWriter();
            var pw = new PrintWriter(sw);
            ex.printStackTrace(pw);

            errorEl.getStyle().setProperty("display", "block");
            errorEl.setInnerHTML(escapeHtml("Predicate violates one or more assertions. " +
                    "The predicate may work in production, but cannot be evaluated here: " + sw));
        }
    }

    private static void filterModrinthVersions(HTMLElement errorEl, HTMLElement countEl) {
        List<String> values = new ArrayList<>();
        for (var input : inputs) {
            String v = input.getValue();
            if (v != null && !v.isBlank()) {
                values.add(v);
            }
        }

        if (values.isEmpty()) {
            errorEl.getStyle().setProperty("display", "none");
            renderList(modrinthVersions, countEl, -1, null);
            return;
        }

        try {
            Collection<VersionPredicate> predicates = createPredicates(values);
            errorEl.getStyle().setProperty("display", "none");
            var filtered = new ArrayList<McEntry>();
            outer:
            for (var entry : modrinthVersions) {
                for (var predicate : predicates) {
                    if (predicate.test(entry.semver())) {
                        filtered.add(entry);
                        continue outer;
                    }
                }
            }
            renderList(filtered, countEl, -1, null);
        } catch (VersionParsingException ex) {
            errorEl.getStyle().setProperty("display", "block");
            errorEl.setInnerHTML(escapeHtml("Invalid predicate: " + ex.getMessage()));
        } catch (AssertionError ex) {
            var sw = new StringWriter();
            var pw = new PrintWriter(sw);
            ex.printStackTrace(pw);

            errorEl.getStyle().setProperty("display", "block");
            errorEl.setInnerHTML(escapeHtml("Predicate violates one or more assertions. " +
                    "The predicate may work in production, but cannot be evaluated here: " + sw));
        }
    }

    private static void renderCustomVersions(HTMLElement errorEl, HTMLElement countEl) {
        List<String> predicateValues = new ArrayList<>();
        for (var input : inputs) {
            String v = input.getValue();
            if (v != null && !v.isBlank()) {
                predicateValues.add(v);
            }
        }

        Collection<VersionPredicate> predicates = null;
        if (!predicateValues.isEmpty()) {
            try {
                predicates = createPredicates(predicateValues);
                errorEl.getStyle().setProperty("display", "none");
            } catch (VersionParsingException ex) {
                errorEl.getStyle().setProperty("display", "block");
                errorEl.setInnerHTML(escapeHtml("Invalid predicate: " + ex.getMessage()));
                return;
            } catch (AssertionError ex) {
                var sw = new StringWriter();
                var pw = new PrintWriter(sw);
                ex.printStackTrace(pw);

                errorEl.getStyle().setProperty("display", "block");
                errorEl.setInnerHTML(escapeHtml("Predicate violates one or more assertions. " +
                        "The predicate may work in production, but cannot be evaluated here: " + sw));
            }
        } else {
            errorEl.getStyle().setProperty("display", "none");
        }

        List<Boolean> matches = new ArrayList<>();
        var entries = customVersionInputs.stream().filter(input -> {
            String v = input.getValue();
            return v != null && !v.isBlank();
        }).map(input -> {
            String v = input.getValue();
            try {
                Version parsed = Version.parse(v);
                return new McEntry(v, parsed);
            } catch (VersionParsingException ignored) {
                // skip entries that don't parse as a version yet (still being typed)
                return null;
            }
        }).filter(Objects::nonNull).sorted(Collections.reverseOrder()).toList();

        for (var entry : entries) {
            if (predicates != null) {
                boolean isMatch = false;
                for (var predicate : predicates) {
                    if (predicate.test(entry.semver())) {
                        isMatch = true;
                        break;
                    }
                }
                matches.add(isMatch);
            } else {
                matches.add(null);
            }
        }

        renderList(entries, countEl, -1, matches);
    }

    private static void renderList(List<McEntry> versions, HTMLElement countEl) {
        renderList(versions, countEl, allVersions.size(), null);
    }

    private static void renderList(List<McEntry> versions, HTMLElement countEl, int total, List<Boolean> matches) {
        var document = HTMLDocument.current();
        var list = document.getElementById("version-list");
        list.setInnerHTML("");
        for (int i = 0; i < versions.size(); i++) {
            var entry = versions.get(i);
            var item = document.createElement("li");
            String matchHtml = "";
            if (matches != null) {
                Boolean m = matches.get(i);
                if (m == null) {
                    matchHtml = "<span class=\"match-empty\"></span>";
                } else {
                    matchHtml = m
                            ? "<span class=\"match-yes\">&#10003; match</span>"
                            : "<span class=\"match-no\">&#10007; no match</span>";
                }
            }
            item.setInnerHTML(
                    "<span class=\"mc-name\">" + escapeHtml(entry.name()) + "</span>" +
                    "<span class=\"semver\">" + escapeHtml(entry.semver().getFriendlyString()) + "</span>" +
                    matchHtml
            );
            list.appendChild(item);
        }
        if (total >= 0) {
            countEl.setInnerHTML(versions.size() + " / " + total + " versions");
        } else {
            countEl.setInnerHTML(versions.size() + " versions");
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Collection<VersionPredicate> createPredicates(Collection<String> ss) throws VersionParsingException {
        return VersionPredicate.parse(ss);
    }

    private static List<McEntry> readVersions(String json) {
        JSObject root = parseJson(json);
        JSArray<JSObject> versions = getVersionsArray(root);
        List<McEntry> list = new ArrayList<>();
        for (int i = 0; i < versions.getLength(); i++) {
            JSObject v = versions.get(i);
            String id = getField(v, "id");
            String normalized = getField(v, "normalized");
            try {
                list.add(new McEntry(id, Version.parse(normalized)));
            } catch (VersionParsingException ignored) {
            }
        }
        return list;
    }

    private static List<McEntry> readModrinthVersions(String json) {
        JSArray<JSObject> versions = parseJsonArray(json);
        List<McEntry> list = new ArrayList<>();
        for (int i = 0; i < versions.getLength(); i++) {
            JSObject v = versions.get(i);
            String versionNumber = getField(v, "version_number");
            String name = getField(v, "name");
            if (versionNumber == null) continue;
            try {
                list.add(new McEntry(name != null ? name : versionNumber, Version.parse(versionNumber)));
            } catch (VersionParsingException ignored) {
            }
        }
        list.sort(Comparator.reverseOrder());
        return list;
    }

    @JSBody(script = "return window.location.search;")
    private static native String getLocationSearch();

    @JSBody(script = "return window.location.origin + window.location.pathname;")
    private static native String getBaseUrl();

    @JSBody(params = "s", script = "return encodeURIComponent(s);")
    private static native String encodeURIComponent(String s);

    @JSBody(params = "s", script = "return decodeURIComponent(s);")
    private static native String decodeURIComponent(String s);

    @JSBody(params = "text", script = "if (navigator.clipboard) { navigator.clipboard.writeText(text); } else { var t = document.createElement('textarea'); t.value = text; document.body.appendChild(t); t.select(); document.execCommand('copy'); document.body.removeChild(t); }")
    private static native void copyToClipboard(String text);

    @JSBody(params = {"el", "text", "delay"}, script = "setTimeout(function() { el.innerHTML = text; }, delay);")
    private static native void resetAfterDelay(HTMLElement el, String text, int delay);

    @JSBody(params = "json", script = "return JSON.parse(json);")
    private static native JSObject parseJson(String json);

    @JSBody(params = "json", script = "return JSON.parse(json);")
    private static native JSArray<JSObject> parseJsonArray(String json);

    @JSBody(params = "root", script = "return root.versions;")
    private static native JSArray<JSObject> getVersionsArray(JSObject root);

    @JSBody(params = {"obj", "key"}, script = "return obj[key];")
    private static native String getField(JSObject obj, String key);

    @JSBody(params = {"input","fetchBtn"}, script = "input.addEventListener('keydown', function(e) { if (e.key === 'Enter') { fetchBtn.click(); } });")
    private static native void addEnterKeyHandler(HTMLInputElement input, HTMLElement fetchBtn);

    private static void applyUrlParams(HTMLDocument document, HTMLElement errorEl, HTMLElement countEl) {
        // custom versions
        var customContainer = document.getElementById("custom-version-inputs");
        List<String> custom = readCustomParams();
        for (String param : custom) {
            addInputBox(document, customContainer, customVersionInputs, () -> refresh(errorEl, countEl), "e.g. 1.20.1");
            customVersionInputs.get(customVersionInputs.size() - 1).setValue(param);
        }

        // mode and modrinth
        String mode = readModeParam();
        String mod = readModrinthParam();
        if (mode != null) {
            try {
                currentMode = Mode.valueOf(mode.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        var predicatePanel = document.getElementById("predicate-panel");
        var modrinthPanel = document.getElementById("modrinth-panel");
        var customPanel = document.getElementById("custom-panel");
        var error = errorEl;

        // ensure radio buttons reflect the current mode
        var mcRadio = (HTMLInputElement) document.getElementById("mode-minecraft");
        var mrRadio = (HTMLInputElement) document.getElementById("mode-modrinth");
        var cuRadio = (HTMLInputElement) document.getElementById("mode-custom");
        mcRadio.setChecked(false);
        mrRadio.setChecked(false);
        cuRadio.setChecked(false);

        switch (currentMode) {
            case MINECRAFT -> {
                mcRadio.setChecked(true);
                predicatePanel.getStyle().setProperty("display", "block");
                modrinthPanel.getStyle().setProperty("display", "none");
                customPanel.getStyle().setProperty("display", "none");
                refresh(error, countEl);
            }
            case MODRINTH -> {
                mrRadio.setChecked(true);
                predicatePanel.getStyle().setProperty("display", "block");
                modrinthPanel.getStyle().setProperty("display", "block");
                customPanel.getStyle().setProperty("display", "none");
                if (mod != null && !mod.isBlank()) {
                    var slugInput = (HTMLInputElement) document.getElementById("modrinth-slug-input");
                    var fetchBtn = document.getElementById("modrinth-fetch-btn");
                    slugInput.setValue(mod);
                    fetchModrinthVersions(mod.trim(), errorEl, countEl, fetchBtn);
                } else {
                    filterModrinthVersions(errorEl, countEl);
                }
            }
            case CUSTOM -> {
                cuRadio.setChecked(true);
                predicatePanel.getStyle().setProperty("display", "block");
                modrinthPanel.getStyle().setProperty("display", "none");
                customPanel.getStyle().setProperty("display", "block");
                renderCustomVersions(errorEl, countEl);
            }
        }
    }

    @JSBody(params = {"el", "text", "delay"}, script = "setTimeout(function() { el.innerHTML = text; }, delay);")
    private static native void resetAfterDelay2(HTMLElement el, String text, int delay);

    @JSBody(params = "json", script = "return JSON.parse(json);")
    private static native JSObject parseJson2(String json);

    @JSBody(params = "json", script = "return JSON.parse(json);")
    private static native JSArray<JSObject> parseJsonArray2(String json);

    @JSBody(params = "root", script = "return root.versions;")
    private static native JSArray<JSObject> getVersionsArray2(JSObject root);

    @JSBody(params = {"obj", "key"}, script = "return obj[key];")
    private static native String getField2(JSObject obj, String key);

    @JSBody(params = "s", script = "return encodeURIComponent(s);")
    private static native String encodeURIComponent2(String s);

    @JSBody(params = "s", script = "return decodeURIComponent(s);")
    private static native String decodeURIComponent2(String s);

    @JSBody(params = "text", script = "if (navigator.clipboard) { navigator.clipboard.writeText(text); } else { var t = document.createElement('textarea'); t.value = text; document.body.appendChild(t); t.select(); document.execCommand('copy'); document.body.removeChild(t); }")
    private static native void copyToClipboard2(String text);

    @JSBody(params = {"obj", "key"}, script = "return obj[key];")
    private static native String getField3(JSObject obj, String key);

    @JSBody(params = "json", script = "return JSON.parse(json);")
    private static native JSObject parseJson3(String json);

    @JSBody(params = "json", script = "return JSON.parse(json);")
    private static native JSArray<JSObject> parseJsonArray3(String json);

    @JSBody(params = "root", script = "return root.versions;")
    private static native JSArray<JSObject> getVersionsArray3(JSObject root);

    @JSBody(params = {"obj", "key"}, script = "return obj[key];")
    private static native String getField4(JSObject obj, String key);

    @JSBody(params = "s", script = "return encodeURIComponent(s);")
    private static native String encodeURIComponent3(String s);

    @JSBody(params = "s", script = "return decodeURIComponent(s);")
    private static native String decodeURIComponent3(String s);

    @JSBody(params = "text", script = "if (navigator.clipboard) { navigator.clipboard.writeText(text); } else { var t = document.createElement('textarea'); t.value = text; document.body.appendChild(t); t.select(); document.execCommand('copy'); document.body.removeChild(t); }")
    private static native void copyToClipboard3(String text);

    @JSBody(params = {"obj", "key"}, script = "return obj[key];")
    private static native String getField5(JSObject obj, String key);

    @JSBody(params = "json", script = "return JSON.parse(json);")
    private static native JSObject parseJson4(String json);

    @JSBody(params = "json", script = "return JSON.parse(json);")
    private static native JSArray<JSObject> parseJsonArray4(String json);

    @JSBody(params = "root", script = "return root.versions;")
    private static native JSArray<JSObject> getVersionsArray4(JSObject root);

    @JSBody(params = {"obj", "key"}, script = "return obj[key];")
    private static native String getField6(JSObject obj, String key);

    @JSBody(params = "s", script = "return encodeURIComponent(s);")
    private static native String encodeURIComponent4(String s);

    @JSBody(params = "s", script = "return decodeURIComponent(s);")
    private static native String decodeURIComponent4(String s);

    @JSBody(params = "text", script = "if (navigator.clipboard) { navigator.clipboard.writeText(text); } else { var t = document.createElement('textarea'); t.value = text; document.body.appendChild(t); t.select(); document.execCommand('copy'); document.body.removeChild(t); }")
    private static native void copyToClipboard4(String text);

    @JSBody(params = {"obj", "key"}, script = "return obj[key];")
    private static native String getField7(JSObject obj, String key);

    record McEntry(String name, Version semver) implements Comparable<McEntry> {
        @Override
        public int compareTo(McEntry o) {
            return semver.compareTo(o.semver);
        }
    }
}
