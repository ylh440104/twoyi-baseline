package io.twoyi;

import java.util.List;

public final class TwinModelCatalogTest {
    public static void main(String[] args) {
        urls();
        statuses();
        catalogs();
        errors();
        System.out.println("PASS: model catalog URL, status mapping and JSON reading");
    }

    private static void urls() {
        expect("https://api.example.com/v1/models".equals(
                TwinModelCatalog.modelsUrl("https://api.example.com/v1")), "plain base");
        expect("https://api.example.com/v1/models".equals(
                TwinModelCatalog.modelsUrl("https://api.example.com/v1/")), "trailing slash");
        expect("https://api.example.com/models".equals(
                TwinModelCatalog.modelsUrl("  https://api.example.com  ")), "trimmed base");
        expect("".equals(TwinModelCatalog.modelsUrl(null)), "null base");
        expect("".equals(TwinModelCatalog.modelsUrl("")), "empty base");
        expect("http://127.0.0.1:8080/models".equals(
                TwinModelCatalog.modelsUrl("http://127.0.0.1:8080//")), "double trailing slash");
    }

    private static void statuses() {
        for (int ok : new int[]{200, 201, 204, 299}) {
            expect(TwinModelCatalog.classify(ok) == TwinModelCatalog.Failure.NONE, "2xx " + ok);
        }
        expect(TwinModelCatalog.classify(401) == TwinModelCatalog.Failure.BAD_KEY, "401");
        expect(TwinModelCatalog.classify(403) == TwinModelCatalog.Failure.BAD_KEY, "403");
        expect(TwinModelCatalog.classify(404) == TwinModelCatalog.Failure.NOT_FOUND, "404");
        expect(TwinModelCatalog.classify(500) == TwinModelCatalog.Failure.SERVER, "500");
        expect(TwinModelCatalog.classify(400) == TwinModelCatalog.Failure.SERVER, "400");
    }

    private static void catalogs() {
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"gpt-4o\",\"object\":\"model\"},"
                + "{\"id\":\"gpt-4o-mini\",\"object\":\"model\"}]}";
        List<String> ids = TwinModelCatalog.parseModelIds(body);
        expect(ids.size() == 2, "two models");
        expect(ids.get(0).equals("gpt-4o"), "first id");
        expect(ids.get(1).equals("gpt-4o-mini"), "second id");
        expect(TwinModelCatalog.isUsable(body), "usable catalog");

        // Spacing variations and an escaped quote inside an id.
        String spaced = "{ \"data\" : [ { \"id\" : \"claude-3-5-sonnet\" } ] }";
        expect(TwinModelCatalog.parseModelIds(spaced).size() == 1, "spaced catalog");
        String escaped = "{\"data\":[{\"id\":\"weird\\\"id\"}]}";
        expect(TwinModelCatalog.parseModelIds(escaped).get(0).equals("weird\\\"id"), "escaped id");

        // Duplicates collapse; empty bodies are empty lists.
        String dupes = "{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"},{\"id\":\"b\"}]}";
        expect(TwinModelCatalog.parseModelIds(dupes).size() == 2, "duplicates collapse");
        expect(TwinModelCatalog.parseModelIds(null).isEmpty(), "null body");
        expect(TwinModelCatalog.parseModelIds("  ").isEmpty(), "blank body");
        expect(TwinModelCatalog.parseModelIds("{\"data\":[]}").isEmpty(), "empty data");
        expect(!TwinModelCatalog.isUsable("{\"data\":[]}"), "empty catalog is unusable");
    }

    private static void errors() {
        String error = "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}";
        expect(TwinModelCatalog.containsErrorObject(error), "error document detected");
        expect(TwinModelCatalog.parseModelIds(error).isEmpty(), "no ids from an error document");
        expect(TwinModelCatalog.errorMessage(error).equals("Incorrect API key provided"), "error message");

        String errors = "{\"errors\":[{\"id\":\"x\"}]}";
        expect(!TwinModelCatalog.containsErrorObject(errors), "errors array is not an error object");
        expect(TwinModelCatalog.errorMessage(errors).isEmpty(), "no message without a message key");
        expect(!TwinModelCatalog.containsErrorObject(null), "null body has no error object");
    }

    private static void expect(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}