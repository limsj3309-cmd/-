package com.limsj.docviewer.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Format detection and dispatch. */
public final class Converter {
    private Converter() {}

    public enum Kind { PDF, DOCX, XLSX, PPTX, HWPX, HWP, DOC, XLS, PPT, UNKNOWN }

    public static Kind detect(File f, String nameHint) {
        byte[] head = new byte[1024];
        int n = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
        } catch (IOException e) {
            return Kind.UNKNOWN;
        }
        String ext = Util.ext(nameHint);
        for (int i = 0; i + 4 < n; i++) {
            if (head[i] == '%' && head[i + 1] == 'P' && head[i + 2] == 'D' && head[i + 3] == 'F') return Kind.PDF;
        }
        if (n >= 4 && head[0] == 'P' && head[1] == 'K') {
            try (ZipRes z = new ZipRes(f)) {
                if (z.has("word/document.xml")) return Kind.DOCX;
                if (z.has("xl/workbook.xml")) return Kind.XLSX;
                if (z.has("ppt/presentation.xml")) return Kind.PPTX;
                if (z.has("Contents/content.hpf") || z.has("Contents/section0.xml")) return Kind.HWPX;
                String main = z.relByType("", "/officeDocument");
                if (main != null) {
                    if (main.startsWith("word/")) return Kind.DOCX;
                    if (main.startsWith("xl/")) return Kind.XLSX;
                    if (main.startsWith("ppt/")) return Kind.PPTX;
                }
            } catch (Exception ignored) {
            }
            return byExt(ext);
        }
        if (Cfb.isCfb(head)) {
            try (Cfb c = new Cfb(f)) {
                if (c.has("FileHeader")) return Kind.HWP;
                if (c.has("WordDocument")) return Kind.DOC;
                if (c.has("Workbook") || c.has("Book")) return Kind.XLS;
                if (c.has("PowerPoint Document")) return Kind.PPT;
                if (c.has("EncryptionInfo") || c.has("EncryptedPackage")) return Kind.UNKNOWN;
            } catch (Exception ignored) {
            }
            return byExt(ext);
        }
        return Kind.UNKNOWN;
    }

    private static Kind byExt(String ext) {
        switch (ext) {
            case "pdf": return Kind.PDF;
            case "docx": case "docm": case "dotx": return Kind.DOCX;
            case "xlsx": case "xlsm": case "xltx": return Kind.XLSX;
            case "pptx": case "pptm": case "ppsx": case "potx": return Kind.PPTX;
            case "hwpx": return Kind.HWPX;
            case "hwp": return Kind.HWP;
            case "doc": case "dot": return Kind.DOC;
            case "xls": case "xlt": return Kind.XLS;
            case "ppt": case "pps": case "pot": return Kind.PPT;
            default: return Kind.UNKNOWN;
        }
    }

    /** Converts any non-PDF document. Caller closes the returned Doc. */
    public static Doc convert(File f, Kind kind) throws Exception {
        switch (kind) {
            case DOCX: case XLSX: case PPTX: case HWPX: {
                ZipRes z = new ZipRes(f);
                try {
                    switch (kind) {
                        case DOCX: return DocxConverter.convert(z);
                        case XLSX: return XlsxConverter.convert(z);
                        case PPTX: return PptxConverter.convert(z);
                        default: return HwpxConverter.convert(z);
                    }
                } catch (Throwable t) {
                    z.close();
                    throw t;
                }
            }
            case HWP: case DOC: case XLS: case PPT: {
                Cfb c = new Cfb(f);
                try {
                    if (c.has("EncryptionInfo")) throw new Doc.UserError("암호가 설정된 문서는 열 수 없습니다.");
                    switch (kind) {
                        case HWP: return HwpConverter.convert(c);
                        case DOC: return DocConverter.convert(c);
                        case XLS: return XlsConverter.convert(c);
                        default: return PptConverter.convert(c);
                    }
                } catch (Throwable t) {
                    c.close();
                    throw t;
                }
            }
            default:
                throw new Doc.UserError("지원하지 않는 파일 형식입니다.");
        }
    }

    /** Detects password-protected OOXML (stored as a compound file). */
    public static boolean isEncryptedOoxml(File f) {
        try (Cfb c = new Cfb(f)) {
            return c.has("EncryptionInfo") || c.has("EncryptedPackage");
        } catch (Exception e) {
            return false;
        }
    }
}
