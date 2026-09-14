package com.ascendersmc.installer.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.*;

public final class FileOps {
    private FileOps() {}
    public static void deleteTree(Path path) throws IOException { if(!Files.exists(path))return; Files.walkFileTree(path,new SimpleFileVisitor<>(){public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Files.deleteIfExists(f);return FileVisitResult.CONTINUE;}public FileVisitResult postVisitDirectory(Path d,IOException e)throws IOException{if(e!=null)throw e;Files.deleteIfExists(d);return FileVisitResult.CONTINUE;}}); }
    public static void moveReplace(Path from,Path to)throws IOException{Files.createDirectories(to.toAbsolutePath().getParent());try{Files.move(from,to,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}}
    public static void copyTree(Path from,Path to)throws IOException{Files.walkFileTree(from,new SimpleFileVisitor<>(){public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{Files.createDirectories(to.resolve(from.relativize(d)));return FileVisitResult.CONTINUE;}public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Files.copy(f,to.resolve(from.relativize(f)),StandardCopyOption.REPLACE_EXISTING);return FileVisitResult.CONTINUE;}});}
    public static void extractZip(Path zip,Path dest)throws IOException{Files.createDirectories(dest);try(ZipInputStream in=new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))){ZipEntry e;while((e=in.getNextEntry())!=null){Path out=dest.resolve(e.getName()).normalize();if(!out.startsWith(dest.normalize()))throw new IOException("ZIP inseguro: "+e.getName());if(e.isDirectory())Files.createDirectories(out);else{Files.createDirectories(out.getParent());Files.copy(in,out,StandardCopyOption.REPLACE_EXISTING);}in.closeEntry();}}}
    public static int countJars(Path dir)throws IOException{if(!Files.isDirectory(dir))return 0;try(var s=Files.list(dir)){return(int)s.filter(Files::isRegularFile).filter(p->p.getFileName().toString().toLowerCase().endsWith(".jar")).count();}}
}
