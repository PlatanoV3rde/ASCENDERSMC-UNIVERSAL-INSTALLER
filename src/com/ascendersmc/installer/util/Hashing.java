package com.ascendersmc.installer.util;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {}
    public static String sha256(Path file) throws IOException { return digest(file, "SHA-256"); }
    public static String sha1(Path file) throws IOException { return digest(file, "SHA-1"); }

    private static String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest md=MessageDigest.getInstance(algorithm);
            try(InputStream in=Files.newInputStream(file)){byte[]b=new byte[256*1024];int n;while((n=in.read(b))>=0)if(n>0)md.update(b,0,n);}
            return HexFormat.of().formatHex(md.digest());
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
