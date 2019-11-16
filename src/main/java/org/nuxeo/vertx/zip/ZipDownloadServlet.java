package org.nuxeo.vertx.zip;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ZipDownloadServlet extends HttpServlet {
    /**
     *
     */
    private static final long serialVersionUID = 1L;
    public static final String FILE_SEPARATOR = System.getProperty("file.separator");

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        // The path below is the root directory of data to be
        // compressed.
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {

            String path = getServletContext().getRealPath("data");

            File directory = new File(path);
            String[] files = directory.list();

            byte[] bytes = new byte[2048];

            for (String fileName : files) {
                String filePath = directory.getPath() + FILE_SEPARATOR + fileName;
                try (FileInputStream fis = new FileInputStream(filePath)) {

                    zos.putNextEntry(new ZipEntry(fileName));

                    int bytesRead;
                    while ((bytesRead = fis.read(bytes)) != -1) {
                        zos.write(bytes, 0, bytesRead);
                    }
                    response.setContentType("application/zip");
                    response.setHeader("Content-Disposition", "attachment; filename=\"DATA.ZIP\"");

                }

            }
        } catch (IOException e) {
            throw new ServletException(e);
        }

    }

}