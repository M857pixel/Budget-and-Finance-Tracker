package com.pocketledger.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CurrencyConversionServiceTest {

    private CurrencyConversionService service;
    private HttpClient mockHttpClient;

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> rateResponse(String rate) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"rate\":" + rate + "}");
        return resp;
    }

    private void stubRate(String rate) throws Exception {
        doReturn(rateResponse(rate)).when(mockHttpClient)
                .send(any(HttpRequest.class), any());
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new CurrencyConversionService();
        mockHttpClient = mock(HttpClient.class);
        inject(service, "httpClient", mockHttpClient);
    }

    @Test
    void getExchangeRateFetchesRateFromFrankfurterApi() throws Exception {
        stubRate("0.9184");
        assertEquals(new BigDecimal("0.9184"), service.getExchangeRate("USD", "EUR"));
    }

    @Test
    void getExchangeRateBuildsCorrectUriWithUppercaseCodes() throws Exception {
        stubRate("1.1");
        service.getExchangeRate("usd", "jpy");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals("https://api.frankfurter.dev/v2/rate/USD/JPY",
                captor.getValue().uri().toString());
    }

    @Test
    void getExchangeRateReturnsOneForIdenticalCurrencies() {
        assertEquals(BigDecimal.ONE, service.getExchangeRate("USD", "usd"));
        verifyNoInteractions(mockHttpClient);
    }

    @Test
    void getExchangeRateRejectsInvalidCurrencyCodes() {
        assertThrows(IllegalArgumentException.class, () -> service.getExchangeRate("US", "EUR"));
        assertThrows(IllegalArgumentException.class, () -> service.getExchangeRate("USD", "EURO"));
        assertThrows(IllegalArgumentException.class, () -> service.getExchangeRate("US1", "EUR"));
        assertThrows(IllegalArgumentException.class, () -> service.getExchangeRate(null, "EUR"));
    }

    @Test
    void getExchangeRateThrowsOnNon200Response() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(404);
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        CurrencyConversionService.CurrencyConversionException e =
                assertThrows(CurrencyConversionService.CurrencyConversionException.class,
                        () -> service.getExchangeRate("USD", "EUR"));
        assertTrue(e.getMessage().contains("HTTP 404"));
    }

    @Test
    void getExchangeRateThrowsWhenResponseContainsNoNumericRate() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"unexpected\":\"shape\"}");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        assertThrows(CurrencyConversionService.CurrencyConversionException.class,
                () -> service.getExchangeRate("USD", "EUR"));
    }

    @Test
    void getExchangeRateWrapsNetworkFailures() throws Exception {
        doThrow(new IOException("unreachable")).when(mockHttpClient)
                .send(any(HttpRequest.class), any());

        assertThrows(CurrencyConversionService.CurrencyConversionException.class,
                () -> service.getExchangeRate("USD", "EUR"));
    }

    @Test
    void getExchangeRateRestoresInterruptFlagOnInterruption() throws Exception {
        doThrow(new InterruptedException("cancelled")).when(mockHttpClient)
                .send(any(HttpRequest.class), any());

        assertThrows(CurrencyConversionService.CurrencyConversionException.class,
                () -> service.getExchangeRate("USD", "EUR"));
        assertTrue(Thread.interrupted());
    }

    @Test
    void convertCurrencyMultipliesAmountByRate() throws Exception {
        stubRate("0.9184");
        assertEquals(new BigDecimal("91.8400"),
                service.convertCurrency(new BigDecimal("100"), "USD", "EUR"));
    }

    @Test
    void convertCurrencyRejectsNullAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> service.convertCurrency(null, "USD", "EUR"));
    }

    @Test
    void formatConversionProducesHumanReadableString() throws Exception {
        stubRate("0.9184");
        assertEquals("50.00 USD = 45.92 EUR (1 USD = 0.9184 EUR)",
                service.formatConversion(new BigDecimal("50"), "usd", "eur"));
    }
}