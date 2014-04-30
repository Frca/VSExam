package com.frca.vsexam.helper;

import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.frca.vsexam.R;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.util.EntityUtils;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Scanner;


public abstract class Helper {
    public static <T> List<T> getValue(List<?> list, String valueName, boolean exclusive) {
        if (list.isEmpty())
            return null;

        Object[] sources = getSource(list, new String[] { valueName } );
        if (sources == null)
            return null;

        Object source = sources[0];

        List<T> newList = new ArrayList<T>();
        for (Object o : list) {
            try {
                T val;
                if (source instanceof Field)
                    val = (T)((Field)source).get(o);
                else
                    val = (T)((Method)source).invoke(o);

                if (!exclusive || !newList.contains(val))
                    newList.add(val);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return newList;
    }

    public static List<ObjectMap> getValuesMap(List<?> list, String[] valuesName, boolean exclusive) {
        if (list.isEmpty())
            return null;

        Object[] sources = getSource(list, valuesName);
        if (sources == null)
            return null;

        List<ObjectMap> newList = new ArrayList<ObjectMap>();
        for (Object o : list) {
            ObjectMap objectValues = new ObjectMap();
            for (int i = 0; i < valuesName.length; ++i) {
                Object source = sources[i];
                try {
                    Object val;
                    if (source instanceof Field)
                        val = ((Field)source).get(o);
                    else
                        val = ((Method)source).invoke(o);

                    objectValues.put(valuesName[i], val);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (!exclusive || !objectValues.isInList(newList))
                newList.add(objectValues);
        }

        return newList;
    }

    private static Object[] getSource(List<?> list, String[] valueNames) {
        Class originalListClass = list.get(0).getClass();
        Object[] sources = new Object[valueNames.length];
        for (int i = 0; i < valueNames.length; ++i) {
            String valueName = valueNames[i];
            String[] values = new String[] {
                valueName,
                "get" + valueName.substring(0, 1).toUpperCase() + valueName.substring(1)
            };

            for (String value : values) {
                try {
                    sources[i] = originalListClass.getField(value);
                    break;
                } catch (NoSuchFieldException e) { }

                try {
                    sources[i] = originalListClass.getMethod(value);
                    break;
                } catch (NoSuchMethodException e) { }
            }

            if (sources[i] == null) {
                Log.e("extractValues", "No such field or method `" + valueName + "` in class " + originalListClass.getName());
                return null;
            }
        }

        return sources;
    }

    public enum DateOutputType {
        DATE(new SimpleDateFormat("dd.MM.yyyy")),
        TIME(new SimpleDateFormat("HH:mm")),
        DATE_TIME(new SimpleDateFormat("dd. MM. yyyy HH:mm")),
        TIME_DATE(new SimpleDateFormat("HH:mm dd. MM. yyyy")),
        FULL(new SimpleDateFormat("dd. MM. yyyy HH:mm:ss.SSS"));

        private SimpleDateFormat mFormat;
        private DateOutputType(SimpleDateFormat format) {
            mFormat = format;
        }

        public String format(Date date) {
            return mFormat.format(date);
        }
    }

    public static String getDateOutput(long milliseconds, DateOutputType outputType) {
        return getDateOutput(new Date(milliseconds), outputType);
    }

    public static String getDateOutput(Date date, DateOutputType outputType) {
        if (date == null)
            return "";
        else if (date.getTime() == 0L)
            return "--";
        else if (outputType == null)
            return String.valueOf(date.getTime()/1000L);
        else
            return outputType.format(date);
    }

    public static boolean isValid(List list) {
        return list != null && !list.isEmpty();
    }

    public static boolean reportErrors = true;
    public static int getResourceValue(Enum value, String prefix) {
        String fieldName = "";
        if (prefix != null)
            fieldName += prefix;
        fieldName += value.toString().toLowerCase();

        try {
            return R.string.class.getField(fieldName).getInt(null);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            if (reportErrors)
                Log.e(Helper.class.getName(), "Missing resource for enum `" + value.toString() + "` (" + fieldName + ")");
        }

        return 0;
    }

    public enum Orientation {
        VERTICAL,
        HORIZONTAL
    }

    public static View getDivider(LayoutInflater inflater, Orientation orientation) {
        ViewGroup.LayoutParams params;
        if (orientation == Orientation.VERTICAL)
            params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        else
            params = new ViewGroup.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT);

        View div = new View(inflater.getContext());
        div.setLayoutParams(params);
        div.setBackgroundResource(R.color.divider_light);

        return div;
    }

    public static String outputRequest(HttpRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getRequestLine().getMethod() + " " + request.getRequestLine().getUri() + " " + request.getRequestLine().getProtocolVersion().toString() + "\n");

        for (Header header : request.getAllHeaders())
            sb.append(header.getName() + ": " + header.getValue() + "\n");

        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
            if (entity.getContentEncoding() != null)
                sb.append(entity.getContentEncoding().getName() + ": " + entity.getContentEncoding().getValue() + "\n");
            if (entity.getContentLength() != 0L)
                sb.append("Content-Length: " + String.valueOf(entity.getContentLength()) + "\n");
            if (entity.getContentType() != null)
                sb.append(entity.getContentType().getName() + ": " + entity.getContentType().getValue() + "\n");

            try {
                sb.append("Content: " + EntityUtils.toString(entity) + "\n");
            } catch (IOException e) { }
        }

        return sb.toString();
    }

    public static String outputResponseHeaders(Map<String, String> request) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : request.entrySet())
            sb.append(entry.getKey() + ": " + entry.getValue() + "\n");

        return sb.toString();
    }

    private static String[] dayPart = new String[] { "den", "dny", "dní" };
    private static String[] hourPart = new String[] { "hodina", "hodiny", "hodin" };
    private static String[] minutePart = new String[] { "minuta", "minuty", "minut" };
    private static String[] secondPart = new String[] { "vteřina", "vteřiny", "vteřin" };

    public static String secondsCountdown(long milis, boolean show_seconds) {
        int total_seconds = (int) (milis / 1000L);
        int days    = (int) Math.floor(total_seconds / 86400);
        int hours   = (int) Math.floor((total_seconds - (days * 86400))/ 3600);
        int minutes = (int) Math.floor((total_seconds - (days * 86400) - (hours * 3600)) / 60);
        int seconds = (show_seconds || minutes == 0) ? (int) Math.floor(total_seconds - (days * 86400) - (hours * 3600) - (minutes * 60)) : 0;

        return (getNamedTimeValue(days, dayPart) + " " +
                getNamedTimeValue(hours, hourPart) + " " +
                getNamedTimeValue(minutes, minutePart) + " " +
                getNamedTimeValue(seconds, secondPart)).trim();
    }

    private static String getNamedTimeValue(int unit, String[] parts) {
        if (unit == 0)
            return "";
        else if (unit == 1)
            return String.valueOf(unit) + " " + parts[0];
        else if (unit < 5)
            return String.valueOf(unit) + " " + parts[1];
        else
            return String.valueOf(unit) + " " + parts[2];
    }

    private static String logFileName = null;

    public static synchronized void appendLog(String text) {
        File logFile;
        if (logFileName == null) {
            logFile = getDataDirectoryFile("log", "output_" + String.valueOf(System.currentTimeMillis() / 1000L), "log");
            logFileName = logFile.getPath();
        } else {
            logFile = new File(logFileName);
        }

        Log.d("Log", text);
        writeToFile(getDateOutput(new Date(), DateOutputType.FULL) + ":  " + text, logFile, true);
    }

    public static String writeToFile(String content, File file, boolean append) {
        try {
            if (file.exists())
                file.createNewFile();

            BufferedWriter buf = new BufferedWriter(new FileWriter(file, append));
            buf.append(content);
            buf.newLine();
            buf.close();
            return null;
        } catch (IOException e) {
            Log.e("File Write", "Cannot write to file " + file.getPath() + "\nError: " + e.getMessage());
            return e.getMessage();
        }
    }

    public static String readFromStream(InputStream is) {
        try {
            return readFromScanner(new Scanner(is));
        } finally {
            Helper.close(is);
        }
    }

    public static String readFromFile(File file) {
        try {
            return readFromScanner(new Scanner(file));
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    private static String readFromScanner(Scanner scanner) {
        try {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : null;
        } finally {
            scanner.close();
        }
    }

    public static File getDataDirectoryFile(String subDir, String filename, String fileType) {
        String path = Environment.getExternalStorageDirectory().getPath() + "/VSExam/";
        if (!TextUtils.isEmpty(subDir))
            path += subDir + "/";

        path += filename + "." + fileType;
        File file = new File(path);
        file.getParentFile().mkdirs();

        return file;
    }

    public static void sleepThread(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) { }
    }

    public static void close(Closeable c) {
        if (c == null)
            return;

        try {
            c.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
