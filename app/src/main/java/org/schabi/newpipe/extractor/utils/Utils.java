package org.schabi.newpipe.extractor.utils;

import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Android 9 (API 28) 互換用 NewPipeExtractor Utils シャドウクラス
 * - Java 10+ の URLDecoder.decode(String, Charset) および URLEncoder.encode(String, Charset) を
 *   API 1+ 互換の URLDecoder.decode(String, "UTF-8") に置き換え、NoSuchMethodError を根本解消
 * - Java 11+ の String.isBlank() を trim().isEmpty() に置き換え、完全互換性を確保
 */
public final class Utils {
    public static final String HTTP = "http://";
    public static final String HTTPS = "https://";
    private static final Pattern M_PATTERN = Pattern.compile("(https?)?://m\\.");
    private static final Pattern WWW_PATTERN = Pattern.compile("(https?)?://www\\.");

    private Utils() {
        // no instance
    }

    /**
     * Encodes a string to URL format using the UTF-8 character set.
     */
    public static String encodeUrlUtf8(final String string) {
        try {
            return URLEncoder.encode(string, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Decodes a URL using the UTF-8 character set.
     */
    public static String decodeUrlUtf8(final String url) {
        try {
            return URLDecoder.decode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Remove all non-digit characters from a string.
     */
    @Nonnull
    public static String removeNonDigitCharacters(@Nonnull final String toRemove) {
        return toRemove.replaceAll("\\D+", "");
    }

    /**
     * Convert a mixed number word to a long.
     */
    public static long mixedNumberWordToLong(final String numberWord)
            throws NumberFormatException, ParsingException {
        String multiplier = "";
        try {
            multiplier = Parser.matchGroup("[\\d]+([\\.,][\\d]+)?([KMBkmb])+", numberWord, 2);
        } catch (final ParsingException ignored) {
        }
        final double count = Double.parseDouble(
                Parser.matchGroup1("([\\d]+([\\.,][\\d]+)?)", numberWord).replace(",", "."));
        switch (multiplier.toUpperCase()) {
            case "K":
                return (long) (count * 1e3);
            case "M":
                return (long) (count * 1e6);
            case "B":
                return (long) (count * 1e9);
            default:
                return (long) (count);
        }
    }

    public static void checkUrl(final String pattern, final String url) throws ParsingException {
        checkUrl(Pattern.compile(pattern), url);
    }

    public static void checkUrl(final Pattern pattern, final String url) throws ParsingException {
        if (isNullOrEmpty(url)) {
            throw new IllegalArgumentException("Url can't be null or empty");
        }
        if (!Parser.isMatch(pattern, url.toLowerCase())) {
            throw new ParsingException("Url doesn't match the pattern");
        }
    }

    public static String replaceHttpWithHttps(final String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith(HTTP)) {
            return HTTPS + url.substring(HTTP.length());
        }
        return url;
    }

    public static String getQueryValue(final URL url, final String key) {
        final String query = url.getQuery();
        if (query != null) {
            final String[] pairs = query.split("&");
            for (final String pair : pairs) {
                final String[] split = pair.split("=", 2);
                final String currentKey = decodeUrlUtf8(split[0]);
                if (currentKey.equals(key)) {
                    return decodeUrlUtf8(split[1]);
                }
            }
        }
        return null;
    }

    public static URL stringToURL(final String url) throws MalformedURLException {
        try {
            return new URL(url);
        } catch (final MalformedURLException e) {
            if (Objects.equals(e.getMessage(), "no protocol: " + url)) {
                return new URL(HTTPS + url);
            }
            throw e;
        }
    }

    public static boolean isHTTP(final URL url) {
        final String protocol = url.getProtocol();
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            return false;
        }
        final boolean isDefaultPort = url.getPort() == url.getDefaultPort();
        final boolean isNoPort = url.getPort() == -1;
        return isNoPort || isDefaultPort;
    }

    public static String removeMAndWWWFromUrl(final String url) {
        if (M_PATTERN.matcher(url).find()) {
            return url.replace("m.", "");
        } else if (WWW_PATTERN.matcher(url).find()) {
            return url.replace("www.", "");
        }
        return url;
    }

    public static String removeUTF8BOM(final String s) {
        String res = s;
        if (res.startsWith("\uFEFF")) {
            res = res.substring(1);
        }
        if (res.endsWith("\uFEFF")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }

    public static String getBaseUrl(final String url) throws ParsingException {
        try {
            final URL uri = stringToURL(url);
            return uri.getProtocol() + "://" + uri.getAuthority();
        } catch (final MalformedURLException e) {
            final String msg = e.getMessage();
            if (msg != null && msg.startsWith("unknown protocol:")) {
                return msg.substring("unknown protocol:".length());
            }
            throw new ParsingException("Unable to get base url from " + url, e);
        }
    }

    public static String followGoogleRedirectIfNeeded(final String url) {
        try {
            final URL u = stringToURL(url);
            if (u.getHost().contains("google") && u.getPath().equals("/url")) {
                return decodeUrlUtf8(Parser.matchGroup1("&url=([^&]+)(?:&|$)", url));
            }
        } catch (final Exception ignored) {
        }
        return url;
    }

    public static boolean isNullOrEmpty(final String string) {
        return string == null || string.isEmpty();
    }

    public static boolean isNullOrEmpty(final Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public static <K, V> boolean isNullOrEmpty(final Map<K, V> map) {
        return map == null || map.isEmpty();
    }

    public static boolean isBlank(final String string) {
        return string == null || string.trim().isEmpty();
    }

    public static String join(final String delimiter, final String keyValueSeparator,
                              final Map<? extends CharSequence, ? extends CharSequence> map) {
        return map.entrySet().stream()
                .map(entry -> entry.getKey() + keyValueSeparator + entry.getValue())
                .collect(Collectors.joining(delimiter));
    }

    public static String nonEmptyAndNullJoin(final CharSequence delimiter, final String... elements) {
        return Arrays.stream(elements)
                .filter(s -> !isNullOrEmpty(s) && !"null".equals(s))
                .collect(Collectors.joining(delimiter));
    }

    public static String getStringResultFromRegexArray(final String text, final String[] regexArray)
            throws Parser.RegexException {
        return getStringResultFromRegexArray(text, regexArray, 0);
    }

    public static String getStringResultFromRegexArray(final String text, final Pattern[] regexArray)
            throws Parser.RegexException {
        return getStringResultFromRegexArray(text, regexArray, 0);
    }

    public static String getStringResultFromRegexArray(final String text, final String[] regexArray, final int group)
            throws Parser.RegexException {
        final Pattern[] patterns = Arrays.stream(regexArray)
                .filter(Objects::nonNull)
                .map(Pattern::compile)
                .toArray(Pattern[]::new);
        return getStringResultFromRegexArray(text, patterns, group);
    }

    public static String getStringResultFromRegexArray(final String text, final Pattern[] regexArray, final int group)
            throws Parser.RegexException {
        for (final Pattern pattern : regexArray) {
            try {
                final String result = Parser.matchGroup(pattern, text, group);
                if (result != null) {
                    return result;
                }
            } catch (final Parser.RegexException ignored) {
            }
        }
        throw new Parser.RegexException("No match found for regexes: " + group);
    }
}
