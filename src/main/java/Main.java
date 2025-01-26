import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.HttpResponse;

public class Main {

    private static final String data_url = "https://budget.gov.ru/epbs/registry/ubpandnubp/data";
    private static final String db_url = "jdbc:postgresql://localhost:5432/interview_database";
    private static final String db_user = "postgres";
    private static final String db_password = "admin";

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        System.out.println("Введите начальную дату (DD.MM.YYYY)");
        String lastUpdateFrom = scanner.nextLine();
        System.out.println("Введите конечную дату (DD.MM.YYYY)");
        String lastUpdateTo = scanner.nextLine();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        LocalDate dateFrom = LocalDate.parse(lastUpdateFrom, formatter);
        LocalDate dateTo = LocalDate.parse(lastUpdateTo, formatter);

        boolean result = lastUpdateFrom.matches("\\d{2}[.]\\d{2}\\.\\d{4}") && lastUpdateTo.matches("\\d{2}[.]\\d{2}\\.\\d{4}") ||
                dateFrom.isBefore(dateTo);
        while (!result) {
            System.out.println("Ошибка ввода дат. Попробуйте ещё раз.");
            System.out.println("Введите начальную дату (DD.MM.YYYY)");
            lastUpdateFrom = scanner.nextLine();
            System.out.println("Введите конечную дату (DD.MM.YYYY)");
            lastUpdateTo = scanner.nextLine();
        }

        try {
            downloadAndSaveData(lastUpdateFrom, lastUpdateTo);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadAndSaveData(String lastUpdateFrom, String lastUpdateTo) {
        try {
            int page = 1;
            int pageCount = 0;

            String quest = data_url + "?filterminloaddate=" + lastUpdateFrom + "&filtermaxloaddate=" + lastUpdateTo + "&page=1";
            URL url = new URL(quest);

            HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
            CloseableHttpClient httpClient = HttpClients.createDefault();
            httpURLConnection.setRequestMethod("GET");
            int responseCode = httpURLConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode jsonResponse = objectMapper.readTree(httpURLConnection.getInputStream());
                JsonNode pageCountNode = jsonResponse.get("pageCount");
                pageCount = pageCountNode.asInt();

            }

            Connection connection = DriverManager.getConnection(db_url, db_user, db_password);
            ObjectMapper objectMapper = new ObjectMapper();

            while (page <= pageCount) {
                String url_request = data_url + "?filterminloaddate=" + lastUpdateFrom + "&filtermaxloaddate=" + lastUpdateTo + "&page=" + page;
                HttpGet request = new HttpGet(url_request);
                HttpResponse response = httpClient.execute(request);

                if (response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_OK) {

                    JsonNode jsonResponse = objectMapper.readTree(response.getEntity().getContent());
                    JsonNode data = jsonResponse.get("data");

                    if (data.isArray() && data.size() > 0) {
                        createFiles(data, lastUpdateFrom, lastUpdateTo);
                        saveRecord(connection, data, lastUpdateFrom, lastUpdateTo);
                    }

                }
                page++;
            }
        System.out.println("Данные успешно сохранены в базе данных и zip-архиве.");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void saveRecord(Connection connection, JsonNode data, String lastUpdateFrom, String lastUpdateTo)
            throws SQLException {

        DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        String dateLastUpdateFrom = lastUpdateFrom;
        String dateLastUpdateTo = lastUpdateTo;

        try {
            LocalDate dateFrom = LocalDate.parse(lastUpdateFrom, inputFormatter);
            LocalDate dateTo = LocalDate.parse(lastUpdateTo, inputFormatter);

            dateLastUpdateFrom = dateFrom.format(outputFormatter);
            dateLastUpdateTo = dateTo.format(outputFormatter);

        } catch (DateTimeParseException e) {
            e.printStackTrace();
        }

        String deleteQuery = "DELETE FROM budget_data WHERE info->>'dateUpdate' BETWEEN ? AND ?";
        try (PreparedStatement deleteStatement = connection.prepareStatement(deleteQuery)) {
            deleteStatement.setString(1, dateLastUpdateFrom);
            deleteStatement.setString(2, dateLastUpdateTo);
            deleteStatement.executeUpdate();
        }

        for (JsonNode record : data) {
            String SQL = "INSERT INTO budget_data (id, info, authorities, activities, heads, successions, " +
                    "facialaccounts, foaccounts, participantpermissions, nonparticipantpermissions, " +
                    "procurementpermissions, contacts, acceptauths, transfauth, attachment, contracts, " +
                    "ubptransfauthbp, ubptransfauthbu, ubpfin, ksaccounts) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement preparedStatement = connection.prepareStatement(SQL)) {
                preparedStatement.setInt(1, record.get("id").asInt());
                preparedStatement.setObject(2, record.get("info"), Types.OTHER);
                preparedStatement.setObject(3, record.get("authorities"), Types.OTHER);
                preparedStatement.setObject(4, record.get("activities"), Types.OTHER);
                preparedStatement.setObject(5, record.get("heads"), Types.OTHER);
                preparedStatement.setObject(6, record.get("successions"), Types.OTHER);
                preparedStatement.setObject(7, record.get("facialAccounts"), Types.OTHER);
                preparedStatement.setObject(8, record.get("foAccounts"), Types.OTHER);
                preparedStatement.setObject(9, record.get("participantPermissions"), Types.OTHER);
                preparedStatement.setObject(10, record.get("nonParticipantPermissions"), Types.OTHER);
                preparedStatement.setObject(11, record.get("procurementPermissions"), Types.OTHER);
                preparedStatement.setObject(12, record.get("contacts"), Types.OTHER);
                preparedStatement.setObject(13, record.get("acceptAuths"), Types.OTHER);
                preparedStatement.setObject(14, record.get("transfauth"), Types.OTHER);
                preparedStatement.setObject(15, record.get("attachment"), Types.OTHER);
                preparedStatement.setObject(16, record.get("contracts"), Types.OTHER);
                preparedStatement.setObject(17, record.get("ubptransfauthbp"), Types.OTHER);
                preparedStatement.setObject(18, record.get("ubptransfauthbu"), Types.OTHER);
                preparedStatement.setObject(19, record.get("ubpfin"), Types.OTHER);
                preparedStatement.setObject(20, record.get("ksaccounts"), Types.OTHER);
                preparedStatement.executeUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void createFiles(JsonNode data, String lastUpdateFrom, String lastUpdateTo) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        XmlMapper xmlMapper = new XmlMapper();
        String jsonFileName = lastUpdateFrom + "_" + lastUpdateTo + "_data.json";
        String xmlFileName = lastUpdateFrom + "_" + lastUpdateTo + "_data.xml";
        File jsonFile = new File(jsonFileName);
        File xmlFile = new File(xmlFileName);
        String zipFilePath = "data.zip";
        File zipFile = new File(zipFilePath);

        ObjectNode rootNode = xmlMapper.createObjectNode();
        ArrayNode xmlArrayNode = xmlMapper.createArrayNode();
        ArrayNode jsonArrayNode = objectMapper.createArrayNode();
        for (JsonNode root : data) {
            xmlArrayNode.add(root);
            jsonArrayNode.add(root);
        }
        rootNode.set("data", xmlArrayNode);
        xmlMapper.writerWithDefaultPrettyPrinter().writeValue(xmlFile, rootNode);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile, jsonArrayNode);

        createZipArchive(zipFile, jsonFile);
        createZipArchive(zipFile, xmlFile);
    }

    private static void createZipArchive(File zipFile, File savedFile) {
        File tempDir = null;
        try {
            if (zipFile.exists()) {
                tempDir = Files.createTempDirectory("zipContents").toFile();
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File newFile = new File(tempDir, entry.getName());
                        if (entry.isDirectory()) {
                            newFile.mkdirs();
                        } else {
                            new File(newFile.getParent()).mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(newFile)) {
                                byte[] buffer = new byte[1024];
                                int bytesRead;
                                while ((bytesRead = zis.read(buffer)) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                }
                            }
                        }
                        zis.closeEntry();
                    }
                }
            }

            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))) {
                if (tempDir != null && tempDir.exists()) {
                    for (File file : tempDir.listFiles()) {
                        if (!file.getName().equals(savedFile.getName())) {
                            try (InputStream in = new FileInputStream(file)) {
                                out.putNextEntry(new ZipEntry(file.getName()));
                                byte[] buffer = new byte[1024];
                                int bytesRead;
                                while ((bytesRead = in.read(buffer)) != -1) {
                                    out.write(buffer, 0, bytesRead);
                                }
                                out.closeEntry();
                            }
                        }
                    }
                }
                try (InputStream in = new FileInputStream(savedFile)) {
                    out.putNextEntry(new ZipEntry(savedFile.getName()));
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.closeEntry();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (tempDir != null) {
                tempDir.delete();
            }
            savedFile.delete();
        }
    }
}


