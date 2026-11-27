package io.twoyi;

public final class TwinProviderRulesTest {
    public static void main(String[] args) {
        keyRules();
        urlRules();
        modelRules();
        System.out.println("PASS: provider form rules reject empty, non-ASCII and non-http input");
    }

    private static void keyRules() {
        expect(TwinProviderRules.checkKey(null) == TwinProviderRules.KeyProblem.REQUIRED, "null key");
        expect(TwinProviderRules.checkKey("   ") == TwinProviderRules.KeyProblem.REQUIRED, "blank key");
        expect(TwinProviderRules.checkKey("sk-abc123") == TwinProviderRules.KeyProblem.NONE, "plain key");
        expect(TwinProviderRules.checkKey("sk-abc 123") == TwinProviderRules.KeyProblem.BAD_CHARS,
                "space inside key");
        expect(TwinProviderRules.checkKey("sk-abc\n123") == TwinProviderRules.KeyProblem.BAD_CHARS,
                "line break inside key");
        expect(TwinProviderRules.checkKey("ｓｋ－ａｂｃ") == TwinProviderRules.KeyProblem.BAD_CHARS,
                "full-width key");
        expect(TwinProviderRules.checkKey("sk-密钥") == TwinProviderRules.KeyProblem.BAD_CHARS,
                "non-english key");
        StringBuilder longKey = new StringBuilder();
        for (int i = 0; i <= TwinProviderRules.MAX_KEY_LENGTH; i++) longKey.append('a');
        expect(TwinProviderRules.checkKey(longKey.toString()) == TwinProviderRules.KeyProblem.BAD_CHARS,
                "over-long key");
    }

    private static void urlRules() {
        expect(TwinProviderRules.checkBaseUrl(null) == TwinProviderRules.UrlProblem.NONE,
                "empty url is optional");
        expect(TwinProviderRules.checkBaseUrl("  ") == TwinProviderRules.UrlProblem.NONE,
                "blank url is optional");
        expect(TwinProviderRules.checkBaseUrl("https://api.example.com/v1")
                == TwinProviderRules.UrlProblem.NONE, "https url");
        expect(TwinProviderRules.checkBaseUrl("http://127.0.0.1:8080")
                == TwinProviderRules.UrlProblem.NONE, "http with port");
        expect(TwinProviderRules.checkBaseUrl("api.example.com")
                == TwinProviderRules.UrlProblem.NOT_HTTP, "missing scheme");
        expect(TwinProviderRules.checkBaseUrl("ftp://api.example.com")
                == TwinProviderRules.UrlProblem.NOT_HTTP, "wrong scheme");
        expect(TwinProviderRules.checkBaseUrl("https://")
                == TwinProviderRules.UrlProblem.MALFORMED, "scheme only");
        expect(TwinProviderRules.checkBaseUrl("https://:443/v1")
                == TwinProviderRules.UrlProblem.MALFORMED, "empty host");
        expect(TwinProviderRules.checkBaseUrl("https://api.example.com:abc")
                == TwinProviderRules.UrlProblem.MALFORMED, "non numeric port");
        expect(TwinProviderRules.checkBaseUrl("https://api.exa mple.com")
                == TwinProviderRules.UrlProblem.MALFORMED, "space in host");
    }

    private static void modelRules() {
        expect(!TwinProviderRules.isModelNameValid(null), "null model");
        expect(!TwinProviderRules.isModelNameValid(""), "empty model");
        expect(!TwinProviderRules.isModelNameValid(" gpt-4o"), "leading space");
        expect(!TwinProviderRules.isModelNameValid("gpt-4o "), "trailing space");
        expect(TwinProviderRules.isModelNameValid("gpt-4o"), "plain model");
        expect(!TwinProviderRules.canConnect("", null, "gpt-4o"), "cannot connect without key");
        expect(!TwinProviderRules.canConnect("sk-1", "api.example.com", "gpt-4o"),
                "cannot connect with a bad url");
        expect(TwinProviderRules.canConnect("sk-1", null, "gpt-4o"), "connect with key only");
        expect(TwinProviderRules.canConnect("sk-1", "https://api.example.com", "gpt-4o"),
                "connect with everything");
    }

    private static void expect(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
