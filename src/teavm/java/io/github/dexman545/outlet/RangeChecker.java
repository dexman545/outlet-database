package io.github.dexman545.outlet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.html.HTMLInputElement;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

public class RangeChecker {
    private static List<McEntry> allVersions = new ArrayList<>();
    private static final List<HTMLInputElement> inputs = new ArrayList<>();
    private static int nextInputId = 0;

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
        var container = document.getElementById("predicate-inputs");
        var errorEl = document.getElementById("error-message");
        var countEl = document.getElementById("match-count");

        renderList(allVersions, countEl);

        List<String> params = readPredicateParams();
        for (String param : params) {
            addInputBox(document, container, errorEl, countEl);
            inputs.get(inputs.size() - 1).setValue(param);
        }
        if (!params.isEmpty()) {
            filterVersions(errorEl, countEl);
        }
        addInputBox(document, container, errorEl, countEl);

        var copyBtn = document.getElementById("copy-url-btn");
        copyBtn.addEventListener("click", e -> {
            copyToClipboard(buildShareUrl());
            copyBtn.setInnerHTML("Copied!");
            resetAfterDelay(copyBtn, "Copy URL", 2000);
        });
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

    private static String buildShareUrl() {
        List<String> values = new ArrayList<>();
        for (var input : inputs) {
            String v = input.getValue();
            if (v != null && !v.isBlank()) {
                values.add(v);
            }
        }
        if (values.isEmpty()) return getBaseUrl();
        StringBuilder sb = new StringBuilder(getBaseUrl()).append("?");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append("&");
            sb.append("p=").append(encodeURIComponent(values.get(i)));
        }
        return sb.toString();
    }

    private static void addInputBox(HTMLDocument document, HTMLElement container, HTMLElement errorEl, HTMLElement countEl) {
        var input = (HTMLInputElement) document.createElement("input");
        input.setAttribute("type", "text");
        input.setAttribute("class", "predicate-input");
        input.setAttribute("placeholder", "e.g. >=1.20.0 <1.21");
        input.setAttribute("autocomplete", "off");
        input.setAttribute("spellcheck", "false");
        input.setAttribute("data-pi-id", String.valueOf(nextInputId++));

        inputs.add(input);
        container.appendChild(input);

        input.addEventListener("input", e -> {
            String lastValue = inputs.get(inputs.size() - 1).getValue();
            if (lastValue != null && !lastValue.isBlank()) {
                addInputBox(document, container, errorEl, countEl);
            }
            filterVersions(errorEl, countEl);
        });

        input.addEventListener("blur", e -> {
            String value = input.getValue();
            if (value != null && !value.isBlank()) return;
            String myId = input.getAttribute("data-pi-id");
            String lastId = inputs.get(inputs.size() - 1).getAttribute("data-pi-id");
            if (myId.equals(lastId)) return; // last input, keep it
            for (int i = 0; i < inputs.size(); i++) {
                if (myId.equals(inputs.get(i).getAttribute("data-pi-id"))) {
                    inputs.remove(i);
                    container.removeChild(input);
                    filterVersions(errorEl, countEl);
                    break;
                }
            }
        });
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
        }
    }

    private static void renderList(List<McEntry> versions, HTMLElement countEl) {
        var document = HTMLDocument.current();
        var list = document.getElementById("version-list");
        list.setInnerHTML("");
        for (var entry : versions) {
            var item = document.createElement("li");
            item.setInnerHTML(
                "<span class=\"mc-name\">" + escapeHtml(entry.name()) + "</span>" +
                "<span class=\"semver\">" + escapeHtml(entry.semver().getFriendlyString()) + "</span>"
            );
            list.appendChild(item);
        }
        countEl.setInnerHTML(versions.size() + " / " + allVersions.size() + " versions");
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

    @JSBody(params = "root", script = "return root.versions;")
    private static native JSArray<JSObject> getVersionsArray(JSObject root);

    @JSBody(params = {"obj", "key"}, script = "return obj[key];")
    private static native String getField(JSObject obj, String key);

    record McEntry(String name, Version semver) implements Comparable<McEntry> {
        @Override
        public int compareTo(McEntry o) {
            return semver.compareTo(o.semver);
        }
    }
}
