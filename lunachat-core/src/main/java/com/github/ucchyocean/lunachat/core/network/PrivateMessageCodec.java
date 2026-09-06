package com.github.ucchyocean.lunachat.core.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Versioned, bounded payloads for network-authoritative private messages. */
public final class PrivateMessageCodec {
    private static final int MAX_NAME = 64, MAX_TEXT = 2048;
    public record Request(UUID sender, String senderName, String targetName, String content) {}
    public record Delivery(UUID sender, String senderName, UUID target, String targetName, String content) {}
    public record Result(String status, UUID target, String targetName, String content) {}
    private static void text(DataOutputStream out, String value, int max) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > max) throw new IllegalArgumentException("text outside bounds");
        out.writeShort(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in, int max) throws IOException {
        int length = in.readUnsignedShort(); if (length < 1 || length > max) throw new IOException("text outside bounds");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
    public byte[] encode(Request value) { try { var b=new ByteArrayOutputStream(); var o=new DataOutputStream(b); o.writeByte(1); o.writeLong(value.sender().getMostSignificantBits()); o.writeLong(value.sender().getLeastSignificantBits()); text(o,value.senderName(),MAX_NAME); text(o,value.targetName(),MAX_NAME); text(o,value.content(),MAX_TEXT); return b.toByteArray(); } catch(IOException e){throw new IllegalStateException(e);} }
    public Request decodeRequest(byte[] bytes) throws IOException { var i=new DataInputStream(new ByteArrayInputStream(bytes)); if(i.readUnsignedByte()!=1) throw new IOException("private payload version"); var id=new UUID(i.readLong(),i.readLong()); var r=new Request(id,text(i,MAX_NAME),text(i,MAX_NAME),text(i,MAX_TEXT)); if(i.available()!=0) throw new IOException("trailing private payload"); return r; }
    public byte[] encode(Delivery value) { try { var b=new ByteArrayOutputStream(); var o=new DataOutputStream(b); o.writeByte(1); o.writeLong(value.sender().getMostSignificantBits()); o.writeLong(value.sender().getLeastSignificantBits()); text(o,value.senderName(),MAX_NAME); o.writeLong(value.target().getMostSignificantBits()); o.writeLong(value.target().getLeastSignificantBits()); text(o,value.targetName(),MAX_NAME); text(o,value.content(),MAX_TEXT); return b.toByteArray(); } catch(IOException e){throw new IllegalStateException(e);} }
    public Delivery decodeDelivery(byte[] bytes) throws IOException { var i=new DataInputStream(new ByteArrayInputStream(bytes)); if(i.readUnsignedByte()!=1) throw new IOException("private payload version"); var s=new UUID(i.readLong(),i.readLong()); var sn=text(i,MAX_NAME); var t=new UUID(i.readLong(),i.readLong()); var r=new Delivery(s,sn,t,text(i,MAX_NAME),text(i,MAX_TEXT)); if(i.available()!=0) throw new IOException("trailing private payload"); return r; }
    public byte[] encode(Result value) { try { var b=new ByteArrayOutputStream(); var o=new DataOutputStream(b); o.writeByte(1); text(o,value.status(),32); o.writeBoolean(value.target()!=null); if(value.target()!=null){o.writeLong(value.target().getMostSignificantBits());o.writeLong(value.target().getLeastSignificantBits());text(o,value.targetName(),MAX_NAME);} o.writeBoolean(value.content()!=null); if(value.content()!=null) text(o,value.content(),MAX_TEXT); return b.toByteArray(); } catch(IOException e){throw new IllegalStateException(e);} }
    public Result decodeResult(byte[] bytes) throws IOException { var i=new DataInputStream(new ByteArrayInputStream(bytes)); if(i.readUnsignedByte()!=1) throw new IOException("private payload version"); String status=text(i,32); UUID target=null; String name=""; if(i.readBoolean()){target=new UUID(i.readLong(),i.readLong());name=text(i,MAX_NAME);} String content=i.readBoolean()?text(i,MAX_TEXT):null; if(i.available()!=0) throw new IOException("trailing private payload"); return new Result(status,target,name,content); }
}
