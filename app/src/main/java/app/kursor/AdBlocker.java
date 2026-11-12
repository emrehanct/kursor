package app.kursor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kursor reklam süzgeci — Adblock Plus / uBlock / AdGuard filtre sözdiziminin yaygın kısmını
 * okuyan, bağımlılıksız saf Java motoru. Ağ isteklerini engeller ve reklam kutularını CSS ile gizler.
 * Sayfaya betik enjekte eden kurallar (scriptlet, #%#, ##+js) bilerek desteklenmez.
 *
 * Kursor ad filter — a dependency-free pure Java engine for the common subset of the
 * Adblock Plus / uBlock / AdGuard filter syntax. Blocks network requests and hides ad boxes with
 * CSS. Rules that inject scripts into pages (scriptlets, #%#, ##+js) are intentionally unsupported.
 *
 * Bir kez kurulduktan sonra değişmez; birden çok iş parçacığından aynı anda okunabilir.
 * Immutable once built; safe to read from many threads at once.
 */
public final class AdBlocker {

    // İstek türleri / request types
    public static final int T_SCRIPT = 1, T_IMAGE = 2, T_CSS = 4, T_XHR = 8, T_SUBDOC = 16,
            T_MEDIA = 32, T_FONT = 64, T_OTHER = 128, T_OBJECT = 256, T_PING = 512, T_WS = 1024;
    private static final int T_ALL = 2047;
    /** Türü kesin bilinmeyen istekler bu türlerden biri sayılır / unknown requests count as any of these */
    public static final int T_UNKNOWN = T_XHR | T_OTHER | T_PING | T_WS | T_SCRIPT | T_OBJECT;

    private static final class NetRule {
        String pattern;          // küçük harf / lowercase
        Pattern regex;           // /.../ kuralları için / for /regex/ rules
        boolean hostAnchor, startAnchor, endAnchor;
        int types = 0;           // 0 = tümü / all
        int party = 0;           // 1 yalnız üçüncü taraf / third-party only, 2 yalnız birinci / first-party only
        String[] incDomains, excDomains;
        boolean important;
    }

    // Ağ kuralları / network rules
    private final Map<String, List<NetRule>> hostBlock = new HashMap<>(), hostAllow = new HashMap<>();
    private final Map<String, List<NetRule>> tokBlock = new HashMap<>(), tokAllow = new HashMap<>();
    private final List<NetRule> anyBlock = new ArrayList<>(), anyAllow = new ArrayList<>();
    private final Set<String> docAllow = new HashSet<>(), genericHide = new HashSet<>(), elemHide = new HashSet<>();

    // Kozmetik kurallar / cosmetic rules
    private final Map<String, List<String>> lowGeneric = new HashMap<>();   // "#id" / ".class" -> seçiciler
    private final List<String> highGeneric = new ArrayList<>();
    private final List<String[]> genericExcept = new ArrayList<>();       // {seçici, hariç alan adları...}
    private final Map<String, List<String>> specific = new HashMap<>();
    private final Map<String, Set<String>> cosAllow = new HashMap<>();
    private final Set<String> cosAllowAll = new HashSet<>();

    private int netCount, cosCount;
    private String highCss = "";
    private final Map<String, String> baseCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 24; }
            });

    public int ruleCount() { return netCount + cosCount; }

    // ------------------------------------------------------------------ yükleme / loading

    /** Boş motor (liste yokken) / empty engine (before lists are available) */
    public static AdBlocker empty() { return new AdBlocker().finish(); }

    public static AdBlocker build(List<Reader> lists) throws IOException {
        AdBlocker ab = new AdBlocker();
        Set<String> bad = new HashSet<>();
        List<String> lines = new ArrayList<>();
        for (Reader r : lists) {
            BufferedReader br = new BufferedReader(r, 1 << 16);
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') continue;
                if (line.contains("$badfilter") || line.contains(",badfilter")) {
                    bad.add(line.replace(",badfilter", "").replace("$badfilter,", "$").replace("$badfilter", ""));
                    continue;
                }
                lines.add(line);
            }
        }
        for (String l : lines) if (!bad.contains(l)) ab.add(l);
        return ab.finish();
    }

    private AdBlocker finish() {
        // Genel istisnaları (#@#seçici) genel listelerden düş / drop global exceptions from generic lists
        if (!cosAllowAll.isEmpty()) {
            highGeneric.removeAll(cosAllowAll);
            for (List<String> l : lowGeneric.values()) l.removeAll(cosAllowAll);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : highGeneric) sb.append(s).append("{display:none!important}\n");
        highCss = sb.toString();
        return this;
    }

    private void add(String line) {
        int ci = cosmeticIndex(line);
        if (ci >= 0) { addCosmetic(line, ci); return; }
        if (line.contains("##") || line.contains("#?#") || line.contains("#$#") || line.contains("#%#")
                || line.contains("#@") || line.contains("$$") || line.contains("$@$")) return;
        addNetwork(line);
    }

    /** "##" ya da "#@#" konumu; desteklenmeyen kozmetik türlerde -1 / position of ## or #@#, -1 if unsupported */
    private static int cosmeticIndex(String l) {
        int i = l.indexOf("##");
        int j = l.indexOf("#@#");
        if (j >= 0 && (i < 0 || j < i)) return j;
        if (i < 0) return -1;
        // ##+js, ##^ (HTML süzme) desteklenmez
        if (l.startsWith("+js", i + 2) || l.startsWith("^", i + 2)) return -2;
        return i;
    }

    private static final String[] PROCEDURAL = {":has-text", ":-abp-", ":matches-css", ":upward", ":xpath",
            ":remove", ":style", ":watch-attr", ":min-text-length", ":others", ":matches-path", ":matches-attr",
            ":matches-media", ":nth-ancestor", ":contains", ":if(", ":if-not", ":matches-prop", ":remove-attr",
            ":remove-class", ":shadow", "{", "}"};

    private void addCosmetic(String line, int idx) {
        if (idx == -2) return;
        boolean exception = line.startsWith("#@#", idx);
        String domains = line.substring(0, idx);
        String sel = line.substring(idx + (exception ? 3 : 2)).trim();
        if (sel.isEmpty()) return;
        for (String p : PROCEDURAL) if (sel.contains(p)) return;
        if (domains.contains("/")) return;   // regex alan adları desteklenmez

        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        if (!domains.isEmpty()) {
            for (String d : domains.split(",")) {
                d = d.trim().toLowerCase();
                if (d.isEmpty()) continue;
                if (d.startsWith("~")) exc.add(d.substring(1)); else inc.add(d);
            }
        }
        if (exception) {
            if (inc.isEmpty()) cosAllowAll.add(sel);
            else for (String d : inc) put(cosAllow, d, sel);
            return;
        }
        cosCount++;
        if (!inc.isEmpty()) {
            for (String d : inc) putList(specific, d, sel);
        } else if (!exc.isEmpty()) {
            String[] e = new String[exc.size() + 1];
            e[0] = sel;
            for (int k = 0; k < exc.size(); k++) e[k + 1] = exc.get(k);
            genericExcept.add(e);
        } else {
            String key = lowKey(sel);
            if (key != null) putList(lowGeneric, key, sel); else highGeneric.add(sel);
        }
    }

    /** Seçici basit bir #id ya da .class ile başlıyorsa o anahtar / the leading #id or .class, if any */
    static String lowKey(String sel) {
        if (sel.length() < 2) return null;
        char c = sel.charAt(0);
        if (c != '#' && c != '.') return null;
        int i = 1;
        while (i < sel.length()) {
            char ch = sel.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') i++;
            else break;
        }
        if (i == 1) return null;
        if (i < sel.length() && sel.charAt(i) == '\\') return null;   // kaçışlı karakter / escaped char
        return sel.substring(0, i);
    }

    private void addNetwork(String line) {
        boolean allow = line.startsWith("@@");
        String s = allow ? line.substring(2) : line;
        String opts = null;
        if (s.startsWith("/")) {
            int last = s.lastIndexOf('/');
            int d = s.indexOf('$', last > 0 ? last : 0);
            if (last > 0 && d > last) { opts = s.substring(d + 1); s = s.substring(0, d); }
        } else {
            int d = s.lastIndexOf('$');
            if (d >= 0) { opts = s.substring(d + 1); s = s.substring(0, d); }
        }
        NetRule r = new NetRule();
        boolean document = false, ghide = false, ehide = false;
        if (opts != null) {
            int posTypes = 0, negTypes = 0;
            for (String o : opts.split(",")) {
                o = o.trim();
                if (o.isEmpty()) continue;
                boolean neg = o.startsWith("~");
                String n = neg ? o.substring(1) : o;
                int t = typeBit(n);
                if (t != 0) { if (neg) negTypes |= t; else posTypes |= t; continue; }
                switch (n) {
                    case "third-party": case "3p": r.party = neg ? 2 : 1; break;
                    case "first-party": case "1p": r.party = neg ? 1 : 2; break;
                    case "important": r.important = true; break;
                    case "all": break;
                    case "document": case "doc": if (!allow) return; document = true; break;
                    case "generichide": case "ghide": if (!allow) return; ghide = true; break;
                    case "elemhide": case "ehide": if (!allow) return; ehide = true; break;
                    default:
                        if (n.startsWith("domain=") || n.startsWith("from=")) {
                            parseDomains(r, n.substring(n.indexOf('=') + 1));
                        } else {
                            return;   // bilinmeyen/desteklenmeyen seçenek: kuralı atla / skip unsupported option
                        }
                }
            }
            if (posTypes != 0) r.types = posTypes;
            else if (negTypes != 0) r.types = T_ALL & ~negTypes;
        }

        if (s.length() > 2 && s.startsWith("/") && s.endsWith("/")) {
            try { r.regex = Pattern.compile(s.substring(1, s.length() - 1), Pattern.CASE_INSENSITIVE); }
            catch (Exception e) { return; }
            r.pattern = "";
        } else {
            s = s.toLowerCase();
            if (s.startsWith("||")) { r.hostAnchor = true; s = s.substring(2); }
            else if (s.startsWith("|")) { r.startAnchor = true; s = s.substring(1); }
            if (s.endsWith("|")) { r.endAnchor = true; s = s.substring(0, s.length() - 1); }
            while (s.startsWith("*")) { s = s.substring(1); r.hostAnchor = false; r.startAnchor = false; }
            while (s.endsWith("*")) { s = s.substring(0, s.length() - 1); r.endAnchor = false; }
            r.pattern = s;
        }

        // Sayfa düzeyi istisnalar / page-level exceptions
        if (document || ghide || ehide) {
            // Yalnız tüm siteyi kapsayan kurallar (||site.com^); yol içerenler atlanır.
            // Only rules covering a whole site (||site.com^); rules with a path are skipped.
            String h = r.hostAnchor ? hostPart(r.pattern) : null;
            if (h == null || r.incDomains != null || !(r.pattern.equals(h) || r.pattern.equals(h + "^")
                    || r.pattern.equals(h + "^|"))) return;
            if (document) docAllow.add(h);
            if (ghide) genericHide.add(h);
            if (ehide) elemHide.add(h);
            return;
        }
        if (r.regex == null && r.pattern.isEmpty() && r.incDomains == null && r.party == 0 && r.types == 0) return;
        netCount++;

        if (r.regex == null && r.hostAnchor) {
            String h = hostPart(r.pattern);
            if (h != null && (r.pattern.equals(h + "^") || r.pattern.equals(h + "^|"))) {
                putList(allow ? hostAllow : hostBlock, h, r);
                return;
            }
        }
        String tok = r.regex == null ? bestToken(r.pattern, allow ? tokAllow : tokBlock) : null;
        if (tok != null) putList(allow ? tokAllow : tokBlock, tok, r);
        else (allow ? anyAllow : anyBlock).add(r);
    }

    /** "örnek.com^..." kalıbının ana makine kısmı / host part of a host-anchored pattern */
    private static String hostPart(String p) {
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') i++;
            else break;
        }
        if (i == 0) return null;
        if (i < p.length() && p.charAt(i) != '^' && p.charAt(i) != '/' && p.charAt(i) != '|') return null;
        String h = p.substring(0, i);
        return h.endsWith(".") ? null : h;
    }

    private static void parseDomains(NetRule r, String v) {
        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        for (String d : v.split("\\|")) {
            d = d.trim().toLowerCase();
            if (d.isEmpty()) continue;
            if (d.startsWith("~")) exc.add(d.substring(1)); else inc.add(d);
        }
        if (!inc.isEmpty()) r.incDomains = inc.toArray(new String[0]);
        if (!exc.isEmpty()) r.excDomains = exc.toArray(new String[0]);
    }

    private static int typeBit(String n) {
        switch (n) {
            case "script": return T_SCRIPT;
            case "image": return T_IMAGE;
            case "stylesheet": case "css": return T_CSS;
            case "xmlhttprequest": case "xhr": return T_XHR;
            case "subdocument": case "frame": return T_SUBDOC;
            case "media": return T_MEDIA;
            case "font": return T_FONT;
            case "other": return T_OTHER;
            case "object": case "object-subrequest": return T_OBJECT;
            case "ping": case "beacon": return T_PING;
            case "websocket": return T_WS;
            default: return 0;
        }
    }

    private static boolean tokChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '%';
    }

    /** Kalıptan dizinleme jetonu seçer (en az kullanılan, en uzun) / picks the rarest, longest token */
    private static String bestToken(String p, Map<String, List<NetRule>> index) {
        String best = null;
        int bestCount = Integer.MAX_VALUE;
        int n = p.length(), i = 0;
        while (i < n) {
            if (!tokChar(p.charAt(i))) { i++; continue; }
            int st = i;
            while (i < n && tokChar(p.charAt(i))) i++;
            if (i - st < 3) continue;
            if (st > 0 && p.charAt(st - 1) == '*') continue;
            if (i < n && p.charAt(i) == '*') continue;
            String t = p.substring(st, i);
            List<NetRule> l = index.get(t);
            int c = l == null ? 0 : l.size();
            if (c < bestCount || (c == bestCount && best != null && t.length() > best.length())) {
                best = t;
                bestCount = c;
            }
        }
        return best;
    }

    private static <T> void putList(Map<String, List<T>> m, String k, T v) {
        List<T> l = m.get(k);
        if (l == null) { l = new ArrayList<>(2); m.put(k, l); }
        l.add(v);
    }

    private static void put(Map<String, Set<String>> m, String k, String v) {
        Set<String> s = m.get(k);
        if (s == null) { s = new HashSet<>(); m.put(k, s); }
        s.add(v);
    }

    // ------------------------------------------------------------------ ağ eşleme / network matching

    /** Sayfa bu alan adında süzülmesin mi (@@...$document) / is filtering disabled on this page */
    public boolean pageAllowed(String pageHost) {
        return pageHost != null && hostIn(docAllow, pageHost);
    }

    /**
     * İstek engellensin mi? / should this request be blocked?
     * @param url      tam adres / full URL
     * @param host     isteğin ana makinesi (küçük harf) / request host (lowercase)
     * @param pageHost sayfanın ana makinesi (küçük harf) / page host (lowercase)
     * @param type     T_* bitleri / T_* bits
     */
    public boolean shouldBlock(String url, String host, String pageHost, int type) {
        if (host == null) return false;
        if (pageHost == null) pageHost = "";
        if (pageAllowed(pageHost)) return false;
        String u = url.toLowerCase();
        boolean third = !pageHost.isEmpty() && !baseDomain(host).equals(baseDomain(pageHost));
        Ctx c = new Ctx(u, host, pageHost, type, third);

        NetRule hit = findHost(hostBlock, c);
        if (hit == null || !hit.important) {
            NetRule h2 = findTok(tokBlock, anyBlock, c);
            if (h2 != null && (hit == null || h2.important)) hit = h2;
        }
        if (hit == null) return false;
        if (hit.important) return true;
        return findHost(hostAllow, c) == null && findTok(tokAllow, anyAllow, c) == null;
    }

    private static final class Ctx {
        final String url, host, page; final int type; final boolean third;
        Ctx(String u, String h, String p, int t, boolean th) { url = u; host = h; page = p; type = t; third = th; }
    }

    private NetRule findHost(Map<String, List<NetRule>> m, Ctx c) {
        String h = c.host;
        while (true) {
            List<NetRule> l = m.get(h);
            if (l != null) for (NetRule r : l) if (optionsOk(r, c)) return r;
            int dot = h.indexOf('.');
            if (dot < 0) return null;
            h = h.substring(dot + 1);
        }
    }

    private NetRule findTok(Map<String, List<NetRule>> idx, List<NetRule> any, Ctx c) {
        String u = c.url;
        NetRule found = null;
        int n = u.length(), i = 0;
        Set<String> seen = null;
        while (i < n) {
            if (!tokChar(u.charAt(i))) { i++; continue; }
            int st = i;
            while (i < n && tokChar(u.charAt(i))) i++;
            if (i - st < 3) continue;
            String t = u.substring(st, i);
            List<NetRule> l = idx.get(t);
            if (l == null) continue;
            if (seen == null) seen = new HashSet<>();
            if (!seen.add(t)) continue;
            for (NetRule r : l) {
                if (optionsOk(r, c) && patternOk(r, c)) {
                    if (r.important) return r;
                    if (found == null) found = r;
                }
            }
        }
        for (NetRule r : any) {
            if (optionsOk(r, c) && patternOk(r, c)) {
                if (r.important) return r;
                if (found == null) found = r;
            }
        }
        return found;
    }

    private static boolean optionsOk(NetRule r, Ctx c) {
        if (r.types != 0 && (r.types & c.type) == 0) return false;
        if (r.party == 1 && !c.third) return false;
        if (r.party == 2 && c.third) return false;
        if (r.excDomains != null && domainIn(r.excDomains, c.page)) return false;
        if (r.incDomains != null && !domainIn(r.incDomains, c.page)) return false;
        return true;
    }

    private static boolean domainIn(String[] list, String host) {
        for (String d : list) {
            if (d.endsWith(".*")) {
                String base = d.substring(0, d.length() - 2);
                if (host.startsWith(base + ".") || host.contains("." + base + ".")) return true;
            } else if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    private static boolean hostIn(Set<String> set, String host) {
        String h = host;
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    private static boolean patternOk(NetRule r, Ctx c) {
        if (r.regex != null) return r.regex.matcher(c.url).find();
        String p = r.pattern, u = c.url;
        if (p.isEmpty()) return true;
        if (r.hostAnchor) {
            // Ana makinenin başı ya da her noktadan sonrası / start of host or after each dot
            int hs = u.indexOf("://");
            if (hs < 0) return false;
            hs += 3;
            int he = hs;
            while (he < u.length() && u.charAt(he) != '/' && u.charAt(he) != ':' && u.charAt(he) != '?') he++;
            if (glob(p, 0, u, hs, r.endAnchor)) return true;
            for (int k = hs; k < he; k++) if (u.charAt(k) == '.' && glob(p, 0, u, k + 1, r.endAnchor)) return true;
            return false;
        }
        if (r.startAnchor) return glob(p, 0, u, 0, r.endAnchor);
        char first = p.charAt(0);
        for (int k = 0; k < u.length(); k++) {
            if (first != '^' && u.charAt(k) != first) continue;
            if (glob(p, 0, u, k, r.endAnchor)) return true;
        }
        return false;
    }

    private static boolean isSep(char c) {
        return !(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '%');
    }

    /** '*' ve '^' destekli kalıp eşleme / glob matching with '*' and '^' */
    static boolean glob(String p, int pi, String s, int si, boolean endAnchor) {
        int pn = p.length(), sn = s.length();
        while (pi < pn) {
            char c = p.charAt(pi);
            if (c == '*') {
                while (pi < pn && p.charAt(pi) == '*') pi++;
                if (pi == pn) return true;
                for (int k = si; k <= sn; k++) if (glob(p, pi, s, k, endAnchor)) return true;
                return false;
            }
            if (c == '^') {
                if (si == sn) { pi++; continue; }   // ^ adresin sonuyla da eşleşir / matches end of URL
                if (!isSep(s.charAt(si))) return false;
                pi++; si++;
                continue;
            }
            if (si >= sn || s.charAt(si) != c) return false;
            pi++; si++;
        }
        return !endAnchor || si == sn;
    }

    /** Yaklaşık kayıtlı alan adı (eTLD+1) / approximate registrable domain */
    public static String baseDomain(String host) {
        int last = host.lastIndexOf('.');
        if (last <= 0) return host;
        int second = host.lastIndexOf('.', last - 1);
        if (second < 0) return host;
        String sld = host.substring(second + 1, last);
        boolean cc = host.length() - last - 1 == 2;
        if (cc && (sld.length() <= 3 || sld.equals("info") || sld.equals("gen"))) {
            int third = host.lastIndexOf('.', second - 1);
            return host.substring(third + 1);
        }
        return host.substring(second + 1);
    }

    // ------------------------------------------------------------------ kozmetik / cosmetic

    /** Bu sayfa için alan adına özel ve genel CSS / domain-specific and broad CSS for a page */
    public String baseCss(String host) {
        if (host == null || host.isEmpty() || pageAllowed(host) || hostIn(elemHide, host)) return "";
        String cached = baseCache.get(host);
        if (cached != null) return cached;
        Set<String> allow = allowedFor(host);
        StringBuilder sb = new StringBuilder();
        for (String key : hostKeys(host)) {
            List<String> l = specific.get(key);
            if (l != null) for (String s : l) if (!allow.contains(s)) sb.append(s).append("{display:none!important}\n");
        }
        if (!hostIn(genericHide, host)) {
            if (allow.isEmpty()) sb.append(highCss);
            else for (String s : highGeneric) if (!allow.contains(s)) sb.append(s).append("{display:none!important}\n");
            for (String[] e : genericExcept) {
                boolean excluded = false;
                for (int k = 1; k < e.length && !excluded; k++) excluded = host.equals(e[k]) || host.endsWith("." + e[k]);
                if (!excluded && !allow.contains(e[0])) sb.append(e[0]).append("{display:none!important}\n");
            }
        }
        String css = sb.toString();
        baseCache.put(host, css);
        return css;
    }

    /** Sayfada görülen id/sınıflara göre genel CSS / generic CSS for ids/classes seen on the page */
    public String tokenCss(String host, List<String> keys) {
        if (host == null || pageAllowed(host) || hostIn(elemHide, host) || hostIn(genericHide, host)) return "";
        Set<String> allow = allowedFor(host);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            List<String> l = lowGeneric.get(k);
            if (l != null) for (String s : l) if (!allow.contains(s)) sb.append(s).append("{display:none!important}\n");
        }
        return sb.toString();
    }

    private Set<String> allowedFor(String host) {
        Set<String> out = null;
        for (String key : hostKeys(host)) {
            Set<String> s = cosAllow.get(key);
            if (s != null) { if (out == null) out = new HashSet<>(); out.addAll(s); }
        }
        return out == null ? Collections.<String>emptySet() : out;
    }

    /** Ana makine son ekleri ve "ornek.*" biçimleri / host suffixes and "example.*" entity forms */
    private static List<String> hostKeys(String host) {
        List<String> keys = new ArrayList<>();
        String h = host;
        while (true) {
            keys.add(h);
            int i = h.indexOf('.');
            while (i > 0) { keys.add(h.substring(0, i) + ".*"); i = h.indexOf('.', i + 1); }
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        return keys;
    }

    /** İstatistik (test için) / stats (for tests) */
    public String stats() {
        int tb = 0, ta = 0;
        for (List<NetRule> l : tokBlock.values()) tb += l.size();
        for (List<NetRule> l : tokAllow.values()) ta += l.size();
        int low = 0;
        for (List<String> l : lowGeneric.values()) low += l.size();
        return "net=" + netCount + " hostBlock=" + hostBlock.size() + " hostAllow=" + hostAllow.size()
                + " tokBlock=" + tb + " tokAllow=" + ta + " anyBlock=" + anyBlock.size() + " anyAllow=" + anyAllow.size()
                + " | cos=" + cosCount + " low=" + low + " high=" + highGeneric.size()
                + " genericExcept=" + genericExcept.size() + " specificHosts=" + specific.size()
                + " highCss=" + highCss.length();
    }
}
