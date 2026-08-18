/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cloudbeaver.service.fs.model;

import com.google.gson.Gson;
import io.cloudbeaver.DBWConstants;
import io.cloudbeaver.DBWebException;
import io.cloudbeaver.model.fs.FsBucketAdmin;
import io.cloudbeaver.model.fs.FsMultipartPart;
import io.cloudbeaver.model.fs.FsMultipartUploader;
import io.cloudbeaver.model.fs.FsObjectInfo;
import io.cloudbeaver.model.fs.WebFSUtils;
import io.cloudbeaver.model.session.WebSession;
import io.cloudbeaver.server.CBApplication;
import io.cloudbeaver.service.WebServiceServletBase;
import io.cloudbeaver.service.fs.DBWServiceFS;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.navigator.fs.DBNPathBase;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.HttpConstants;
import org.jkiss.utils.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@MultipartConfig()
public class WebFSServlet extends WebServiceServletBase {

    private static final Log log = Log.getLog(WebFSServlet.class);
    private static final String PARAM_PROJECT_ID = "projectId";
    private static final String ATTR_MULTIPART = "cloud-storage-multipart";
    private static final Gson GSON = new Gson();
    private final DBWServiceFS fs;

    public WebFSServlet(CBApplication<?> application, DBWServiceFS fs) {
        super(application);
        this.fs = fs;
    }

    @Override
    protected void processServiceRequest(WebSession session, HttpServletRequest request, HttpServletResponse response) throws DBException, IOException {
        if (!session.isAuthorizedInSecurityManager()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Anonymous access restricted.");
            return;
        }
        if (request.getMethod().equals("POST")) {
            if (DBWorkbench.isDistributed() && !session.hasPermission(DBWConstants.PERMISSION_SQL_RESULT_UPDATE)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Permission denied.");
                return;
            }
            doPost(session, request, response);
        } else {
            doGet(session, request, response);
        }

    }

    private void doGet(WebSession session, HttpServletRequest request, HttpServletResponse response) throws DBException, IOException {
        Path path = WebFSUtils.getPathFromNode(session, request.getParameter("nodePath"));
        String versionId = request.getParameter("versionId");
        session.addInfoMessage("Download data ...");
        response.setHeader(HttpConstants.HEADER_CONTENT_TYPE, "application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + path.getFileName() + "\"");

        FsBucketAdmin admin = !CommonUtils.isEmpty(versionId) ? FsBucketAdmin.of(path) : null;
        if (admin != null) {
            FsObjectInfo info = admin.getObjectInfo(path, versionId);
            response.setHeader("Content-Length", String.valueOf(info.size()));
            try (InputStream is = admin.openObject(path, versionId)) {
                IOUtils.copyStream(is, response.getOutputStream());
            }
            return;
        }

        response.setHeader("Content-Length", String.valueOf(Files.size(path)));
        try (InputStream is = Files.newInputStream(path)) {
            IOUtils.copyStream(is, response.getOutputStream());
        }
    }

    private void doPost(WebSession session, HttpServletRequest request, HttpServletResponse response) throws DBException, IOException {
        request.setAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT, new MultipartConfigElement(""));
        Map<String, Object> variables = getVariables(request);
        if (variables == null) {
            throw new DBException("Parent node path parameter is not found");
        }
        String action = JSONUtils.getString(variables, "action");
        if (CommonUtils.isEmpty(action) || "upload".equals(action)) {
            uploadSingle(session, request, variables);
            return;
        }
        try {
            switch (action) {
                case "multipartStart" -> writeJson(response, multipartStart(session, variables));
                case "multipartPart" -> writeJson(response, multipartPart(session, request, variables));
                case "multipartComplete" -> writeJson(response, multipartComplete(session, variables));
                case "multipartAbort" -> writeJson(response, multipartAbort(session, variables));
                default -> throw new DBException("Unknown upload action: " + action);
            }
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("File Upload Failed: Unable to Save File to the File System", CommonUtils.getRootCause(e));
        }
    }

    private void uploadSingle(WebSession session, HttpServletRequest request, Map<String, Object> variables) throws DBException, IOException {
        DBNPathBase node = parentNode(session, variables);
        Path path = node.getPath();
        boolean resume = CommonUtils.toBoolean(variables.get("resume"));
        try {
            for (Part part : request.getParts()) {
                String fileName = part.getSubmittedFileName();
                if (CommonUtils.isEmpty(fileName)) {
                    continue;
                }
                Path safeTarget = resolveSafeChild(path, fileName);
                if (resume && Files.exists(safeTarget)) {
                    continue;
                }
                try (InputStream is = part.getInputStream()) {
                    Files.copy(is, safeTarget, StandardCopyOption.REPLACE_EXISTING);
                    node.addChildResource(safeTarget);
                }
            }
        } catch (Exception e) {
            throw new DBWebException("File Upload Failed: Unable to Save File to the File System",
                CommonUtils.getRootCause(e));
        }
    }

    @NotNull
    private Map<String, Object> multipartStart(WebSession session, Map<String, Object> variables) throws Exception {
        DBNPathBase node = parentNode(session, variables);
        String fileName = JSONUtils.getString(variables, "fileName");
        if (CommonUtils.isEmpty(fileName)) {
            throw new DBException("fileName is required");
        }
        Path dest = resolveSafeChild(node.getPath(), fileName);
        FsMultipartUploader uploader = requireUploader(dest);
        String uploadId = uploader.startMultipart(dest);
        multipartSessions(session).put(uploadId, dest);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadId", uploadId);
        result.put("partSize", FsMultipartUploader.PART_SIZE);
        return result;
    }

    @NotNull
    private Map<String, Object> multipartPart(
        WebSession session,
        HttpServletRequest request,
        Map<String, Object> variables
    ) throws Exception {
        String uploadId = JSONUtils.getString(variables, "uploadId");
        int partNumber = toInt(variables.get("partNumber"));
        Path dest = requireSessionDest(session, uploadId);
        FsMultipartUploader uploader = requireUploader(dest);
        String etag = null;
        for (Part part : request.getParts()) {
            if (CommonUtils.isEmpty(part.getSubmittedFileName())) {
                continue;
            }
            try (InputStream is = part.getInputStream()) {
                etag = uploader.uploadPart(dest, uploadId, partNumber, is, part.getSize());
            }
            break;
        }
        if (CommonUtils.isEmpty(etag)) {
            throw new DBException("Multipart part body is missing");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("etag", etag);
        result.put("partNumber", partNumber);
        return result;
    }

    @NotNull
    private Map<String, Object> multipartComplete(WebSession session, Map<String, Object> variables) throws Exception {
        String uploadId = JSONUtils.getString(variables, "uploadId");
        Path dest = requireSessionDest(session, uploadId);
        FsMultipartUploader uploader = requireUploader(dest);
        List<FsMultipartPart> parts = new ArrayList<>();
        Object rawParts = variables.get("parts");
        if (rawParts instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    parts.add(new FsMultipartPart(toInt(map.get("partNumber")), String.valueOf(map.get("etag"))));
                }
            }
        }
        parts.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));
        uploader.completeMultipart(dest, uploadId, parts);
        multipartSessions(session).remove(uploadId);
        DBNPathBase parent = parentNode(session, variables);
        parent.addChildResource(dest);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    @NotNull
    private Map<String, Object> multipartAbort(WebSession session, Map<String, Object> variables) throws Exception {
        String uploadId = JSONUtils.getString(variables, "uploadId");
        Path dest = requireSessionDest(session, uploadId);
        requireUploader(dest).abortMultipart(dest, uploadId);
        multipartSessions(session).remove(uploadId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    @NotNull
    private DBNPathBase parentNode(WebSession session, Map<String, Object> variables) throws DBException {
        String parentNodePath = JSONUtils.getString(variables, "toParentNodePath");
        if (CommonUtils.isEmpty(parentNodePath)) {
            throw new DBException("Parent node path parameter is not found");
        }
        return WebFSUtils.getNodeByPath(session, parentNodePath);
    }

    @NotNull
    private FsMultipartUploader requireUploader(@NotNull Path dest) throws DBException {
        FsMultipartUploader uploader = FsMultipartUploader.of(dest);
        if (uploader == null) {
            throw new DBException("Multipart upload is not supported for this storage");
        }
        return uploader;
    }

    @NotNull
    private Path requireSessionDest(WebSession session, String uploadId) throws DBException {
        if (CommonUtils.isEmpty(uploadId)) {
            throw new DBException("uploadId is required");
        }
        Path dest = multipartSessions(session).get(uploadId);
        if (dest == null) {
            throw new DBException("Unknown multipart upload");
        }
        return dest;
    }

    @NotNull
    private Map<String, Path> multipartSessions(WebSession session) {
        Map<String, Path> sessions = session.getAttribute(ATTR_MULTIPART);
        if (sessions == null) {
            sessions = new ConcurrentHashMap<>();
            session.setAttribute(ATTR_MULTIPART, sessions);
        }
        return sessions;
    }

    private int toInt(Object value) throws DBException {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new DBException("Invalid part number");
    }

    private void writeJson(HttpServletResponse response, Map<String, Object> body) throws IOException {
        response.setHeader(HttpConstants.HEADER_CONTENT_TYPE, "application/json");
        response.getWriter().write(GSON.toJson(body));
    }

    @NotNull
    private Path resolveSafeChild(@NotNull Path parent, @NotNull String submittedFileName) throws DBException {
        Path candidate;
        try {
            candidate = Path.of(submittedFileName);
        } catch (InvalidPathException e) {
            throw new DBException("Invalid file name");
        }
        Path baseName = candidate.getFileName();
        if (baseName == null || baseName.toString().isBlank()) {
            throw new DBException("Invalid file name");
        }
        if (submittedFileName.isBlank()
            || ".".equals(submittedFileName)
            || "..".equals(submittedFileName)
            || submittedFileName.indexOf('/') >= 0
            || submittedFileName.indexOf('\\') >= 0
        ) {
            throw new DBException("Invalid file name");
        }
        try {
            return parent.normalize().resolve(submittedFileName).normalize();
        } catch (InvalidPathException e) {
            throw new DBException("Invalid file name");
        }
    }

    @Override
    protected Map<String, Object> getVariables(HttpServletRequest request) {
        Map<String, Object> variables = super.getVariables(request);
        if (request.getMethod().equals("POST")) {
            try {
                for (Part part : request.getParts()) {
                    if (part.getSubmittedFileName() != null && !part.getSubmittedFileName().isEmpty()) {
                        variables.put("fileName", part.getSubmittedFileName());
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to get fileName from request for logging", e);
            }
        }
        return variables;
    }
}
