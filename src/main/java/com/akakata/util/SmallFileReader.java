package com.akakata.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Kelvin
 */
public class SmallFileReader {

    public static String readSmallFile(String filePath) throws IOException {
        if (null == filePath || filePath.isEmpty()) {
            return null;
        }
        File smallFile = new File(filePath);
        return readSmallFile(smallFile);
    }

    public static String readSmallFile(File smallFile) throws IOException {
        // FileReader reader = new FileReader(smallFile);
        InputStreamReader reader = new InputStreamReader(Files.newInputStream(smallFile.toPath()), StandardCharsets.UTF_8);
        BufferedReader bufferedReader = new BufferedReader(reader);
        StringBuilder buf = new StringBuilder();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            buf.append(line);
        }
        bufferedReader.close();
        reader.close();
        return buf.toString();
    }

    public static byte[] readFileBytes(String filePath) throws IOException {
        if (null == filePath || filePath.isEmpty()) {
            return null;
        }
        File smallFile = new File(filePath);
        return readFileBytes(smallFile);
    }

    public static byte[] readFileBytes(File smallFile) throws IOException {
        ByteArrayOutputStream ous = null;
        InputStream ios = null;
        try {
            byte[] buffer = new byte[4096];
            ous = new ByteArrayOutputStream();
            ios = Files.newInputStream(smallFile.toPath());
            int read;
            while ((read = ios.read(buffer)) != -1) {
                ous.write(buffer, 0, read);
            }
        } finally {
            try {
                if (ous != null) {
                    ous.close();
                }
            } catch (IOException e) {
                // swallow, since not that important
            }
            try {
                if (ios != null) {
                    ios.close();
                }
            } catch (IOException e) {
                // swallow, since not that important
            }
        }
        return ous.toByteArray();
    }

    public static List<String> listFile(String path, String suffix) {
        List<String> list = new ArrayList<>();

        File file = new File(path);
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File value : files) {
                    String filePath = value.getName();
                    if (suffix != null) {
                        int begIndex = filePath.lastIndexOf(".");
                        String tempsuffix = "";
                        if (begIndex != -1) {
                            tempsuffix = filePath.substring(begIndex + 1);
                        }
                        if (tempsuffix.equals(suffix)) {
                            list.add(filePath);
                        }
                    } else {
                        list.add(filePath);
                    }
                }
            }
        }

        return list;
    }
}
