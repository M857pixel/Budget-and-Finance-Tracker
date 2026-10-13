/*

    This program gets currecy exchange rates from Frankfurter API and is able to translate between different currencies

    Usage:

    1. Go to api directory

    2.  mvn compile
        mvn exec:java -Dexec.mainClass=Frankfurter -Dexec.args="<from-currency> <to-currency> <amount>" // i.e. mvn exec:java -Dexec.mainClass=Frankfurter -Dexec.args="USD EUR 25"


 */

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Frankfurter {
    private static final String API = "https://api.frankfurter.dev/v2";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: Frankfurter <from-currency> <to-currency> <amount>");
            System.err.println("Example: Frankfurter USD EUR 100");
            System.exit(1);
        }

        String from = validateCurrencyCode(args[0]);
        String to = validateCurrencyCode(args[1]);

        BigDecimal amount;
        try {
            amount = new BigDecimal(args[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Amount must be a valid number, for example 100 or 12.50", e);
        }

        BigDecimal rate = getRate(from, to);
        BigDecimal converted = amount.multiply(rate);

        System.out.println("1 " + from + " = " + rate + " " + to);
        System.out.println(amount + " " + from + " = " + converted + " " + to);
    }

    /** Fetches the latest rate for one currency pair. */
    public static BigDecimal getRate(String from, String to) throws Exception {
        String base = validateCurrencyCode(from);
        String quote = validateCurrencyCode(to);

        if (base.equals(quote)) {
            return BigDecimal.ONE;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API + "/rate/" + base + "/" + quote))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalArgumentException(
                    "Rate request failed (" + response.statusCode() + "): "
                            + response.body());
        }

        JsonNode root = JSON.readTree(response.body());
        JsonNode rateNode = root.get("rate");

        if (rateNode == null || !rateNode.isNumber()) {
            throw new IllegalStateException(
                    "Missing or invalid rate in API response: " + response.body());
        }

        return rateNode.decimalValue();
    }

    /** Converts an amount from one currency to another using the latest rate. */
    public static BigDecimal convert(
            BigDecimal amount, String from, String to) throws Exception {
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }

        return amount.multiply(getRate(from, to));
    }

    private static String validateCurrencyCode(String code) {
        if (!isThreeAsciiLetters(code)) {
            throw new IllegalArgumentException(
                    "Currency code must be three letters, e.g. USD");
        }
        return code.toUpperCase();
    }

    private static boolean isThreeAsciiLetters(String value) {
        if (value == null || value.length() != 3) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }

        return true;
    }
}

