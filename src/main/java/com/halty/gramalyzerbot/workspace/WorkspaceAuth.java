package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Component
public class WorkspaceAuth extends OncePerRequestFilter {
    private final boolean demo; private final String token; private final ObjectMapper mapper;
    /** Signs session cookies, so a sign-in survives a server restart: the key is derived from the token (random in the demo). */
    private final byte[] sessionKey;
    public WorkspaceAuth(@Value("${gramalyzer.demo:false}") boolean demo, @Value("${gramalyzer.api-token:}") String token, ObjectMapper mapper) {
        this.demo=demo; this.token=token; this.mapper=mapper;
        if (!demo && token.isBlank()) throw new IllegalStateException("GRAMALYZER_API_TOKEN is required outside the demo profile.");
        byte[] key=new byte[32]; new SecureRandom().nextBytes(key);
        try {if (!token.isBlank()) key=MessageDigest.getInstance("SHA-256").digest(("gramalyzer-session:"+token).getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        this.sessionKey=key;
    }
    /** "expiry.nonce.signature": checked by recomputing the signature, so nothing needs storing. Changing the token signs everyone out. */
    String newSession(Instant expires) {byte[] nonce=new byte[16]; new SecureRandom().nextBytes(nonce); String body=expires.getEpochSecond()+"."+HexFormat.of().formatHex(nonce); return body+"."+sign(body);}
    boolean validSession(String value) {
        if (value == null) return false;
        int dot=value.lastIndexOf('.'); if (dot<0) return false;
        String body=value.substring(0,dot);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),value.substring(dot+1).getBytes(StandardCharsets.UTF_8))) return false;
        try {return Instant.ofEpochSecond(Long.parseLong(body.substring(0,body.indexOf('.')))).isAfter(Instant.now());}
        catch(RuntimeException e) {return false;}
    }
    private String sign(String body) {
        try {javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(new javax.crypto.spec.SecretKeySpec(sessionKey,"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException e) {throw new IllegalStateException(e);}
    }
    private boolean equal(String value) {return value != null && !token.isBlank() && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),value.getBytes(StandardCharsets.UTF_8));}
    private void fail(HttpServletResponse res,int status,String message) throws IOException {res.setStatus(status); res.setContentType("application/json"); mapper.writeValue(res.getOutputStream(),Map.of("error",message));}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI(); if (!path.startsWith("/api/")) {chain.doFilter(req,res); return;}
        res.setHeader("Cache-Control","no-store");
        if (!Set.of("GET","HEAD").contains(req.getMethod()) && req.getHeader("Origin") != null) {
            try {if (!Objects.equals(URI.create(req.getHeader("Origin")).getRawAuthority(),req.getHeader("Host")) && !(demo && Set.of("http://localhost:5173","http://127.0.0.1:5173").contains(req.getHeader("Origin")))) {fail(res,403,"Cross-origin requests are not allowed."); return;}}
            catch(IllegalArgumentException e) {fail(res,403,"Invalid request origin."); return;}
        }
        if (path.equals("/api/health")) {chain.doFilter(req,res); return;}
        if (path.equals("/api/login") && req.getMethod().equals("POST")) {
            String supplied; try {supplied=mapper.readTree(req.getInputStream()).path("token").asText();} catch(Exception e) {fail(res,400,"Enter a workspace access token."); return;}
            if (!demo && !equal(supplied)) {fail(res,401,"Invalid workspace access token."); return;}
            String id=newSession(Instant.now().plusSeconds(28800));
            res.addHeader("Set-Cookie","gramalyzer_session="+id+"; HttpOnly; SameSite=Strict; Path=/api; Max-Age=28800"+(req.isSecure()?"; Secure":""));
            res.setContentType("application/json"); mapper.writeValue(res.getOutputStream(),Map.of("ok",true)); return;
        }
        if (demo) {chain.doFilter(req,res); return;}
        String auth=req.getHeader("Authorization"); boolean signed=auth != null && auth.startsWith("Bearer ") && equal(auth.substring(7));
        if (req.getCookies()!=null) for (Cookie cookie:req.getCookies()) if (cookie.getName().equals("gramalyzer_session") && validSession(cookie.getValue())) signed=true;
        if (!signed) {fail(res,401,"Sign in to your workspace."); return;}
        chain.doFilter(req,res);
    }
}
