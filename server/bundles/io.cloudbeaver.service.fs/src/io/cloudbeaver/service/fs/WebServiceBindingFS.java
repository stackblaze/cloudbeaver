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
package io.cloudbeaver.service.fs;

import io.cloudbeaver.DBWebException;
import io.cloudbeaver.server.CBApplication;
import io.cloudbeaver.service.DBWBindingContext;
import io.cloudbeaver.service.DBWServiceBindingServlet;
import io.cloudbeaver.service.DBWServletContext;
import io.cloudbeaver.service.WebServiceBindingBase;
import io.cloudbeaver.service.fs.impl.WebServiceFS;
import io.cloudbeaver.service.fs.model.WebFSServlet;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web service implementation
 */
public class WebServiceBindingFS extends WebServiceBindingBase<DBWServiceFS> implements DBWServiceBindingServlet<CBApplication<?>> {

    private static final String SCHEMA_FILE_NAME = "schema/service.fs.graphqls";

    public WebServiceBindingFS() {
        super(DBWServiceFS.class, new WebServiceFS(), SCHEMA_FILE_NAME);
    }

    @Override
    public void bindWiring(DBWBindingContext model) throws DBWebException {
        model.getQueryType()
            .dataFetcher("fsListFileSystems",
                env -> getService(env).getAvailableFileSystems(getWebSession(env), getArgumentVal(env, "projectId")))
            .dataFetcher("fsFileSystem",
                env -> getService(env).getFileSystem(
                    getWebSession(env),
                    getArgumentVal(env, "projectId"),
                    getArgumentVal(env, "nodePath")
                )
            )
            .dataFetcher("fsFile",
                env -> getService(env).getFile(getWebSession(env),
                    getArgumentVal(env, "nodePath")
                )
            )
            .dataFetcher("fsListFiles",
                env -> getService(env).getFiles(getWebSession(env),
                    getArgumentVal(env, "folderPath")
                )
            )
            .dataFetcher("fsReadFileContentAsString",
                env -> getService(env).readFileContent(getWebSession(env),
                    getArgumentVal(env, "nodePath")
                )
            )
            .dataFetcher("fsGetBucketPolicy",
                env -> getService(env).getBucketPolicy(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsGetBucketNotification",
                env -> getService(env).getBucketNotification(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsGetStackblazeContext",
                env -> getService(env).getStackblazeContext(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsGetBucketVersioning",
                env -> getService(env).getBucketVersioning(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsGetBucketEncryption",
                env -> getService(env).getBucketEncryption(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsGetBucketTags",
                env -> getService(env).getBucketTags(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsGetObjectTags",
                env -> getService(env).getObjectTags(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsGetObjectInfo",
                env -> getService(env).getObjectInfo(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgument(env, "versionId")
                )
            )
            .dataFetcher("fsListObjectVersions",
                env -> getService(env).listObjectVersions(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
        ;
        model.getMutationType()
            .dataFetcher("fsCreateFile",
                env -> getService(env).createFile(getWebSession(env),
                    getArgumentVal(env, "parentPath"),
                    getArgumentVal(env, "fileName")
                )
            )
            .dataFetcher("fsCreateFolder",
                env -> getService(env).createFolder(getWebSession(env),
                    getArgumentVal(env, "parentPath"),
                    getArgumentVal(env, "folderName")
                    )
            )
            .dataFetcher("fsDelete",
                env -> getService(env).deleteFile(getWebSession(env),
                    getArgumentVal(env, "nodePath")
                )
            )
            .dataFetcher("fsMove",
                env -> getService(env).moveFile(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "toParentNodePath")
                )
            )
            .dataFetcher("fsRename",
                env -> getService(env).renameFile(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "newName")
                )
            )
            .dataFetcher("fsCopy",
                env -> getService(env).copyFile(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "toParentNodePath")
                )
            )
            .dataFetcher("fsTransfer",
                env -> getService(env).transferFiles(
                    getWebSession(env),
                    getArgumentVal(env, "nodePaths"),
                    getArgumentVal(env, "toParentNodePath"),
                    String.valueOf(getArgumentVal(env, "mode")),
                    CommonUtils.toBoolean(getArgument(env, "resume"))
                )
            )
            .dataFetcher("fsWriteFileStringContent",
                env -> getService(env).writeFileContent(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "data"),
                    CommonUtils.toBoolean(getArgument(env, "forceOverwrite"))
                )
            )
            .dataFetcher("fsSetBucketPolicy",
                env -> getService(env).setBucketPolicy(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "policy")
                )
            )
            .dataFetcher("fsSetBucketNotification",
                env -> getService(env).setBucketNotification(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "events"),
                    CommonUtils.notEmpty(getArgument(env, "targetArn"))
                )
            )
            .dataFetcher("fsRemoveBucketNotification",
                env -> getService(env).removeBucketNotification(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath")
                )
            )
            .dataFetcher("fsSetBucketVersioning",
                env -> getService(env).setBucketVersioning(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "status")
                )
            )
            .dataFetcher("fsSetBucketEncryption",
                env -> getService(env).setBucketEncryption(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "algorithm"),
                    getArgument(env, "kmsKeyId")
                )
            )
            .dataFetcher("fsRemoveBucketEncryption",
                env -> getService(env).removeBucketEncryption(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsSetBucketTags",
                env -> getService(env).setBucketTags(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    toTagMap(getArgument(env, "tags"))
                )
            )
            .dataFetcher("fsSetObjectTags",
                env -> getService(env).setObjectTags(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    toTagMap(getArgument(env, "tags"))
                )
            )
            .dataFetcher("fsDeleteObjectTags",
                env -> getService(env).deleteObjectTags(getWebSession(env), getArgumentVal(env, "nodePath"))
            )
            .dataFetcher("fsDeleteObjectVersion",
                env -> getService(env).deleteObjectVersion(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "versionId")
                )
            )
            .dataFetcher("fsRestoreObjectVersion",
                env -> getService(env).restoreObjectVersion(
                    getWebSession(env),
                    getArgumentVal(env, "nodePath"),
                    getArgumentVal(env, "versionId")
                )
            )
        ;
    }

    @NotNull
    private static Map<String, String> toTagMap(Object raw) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (!(raw instanceof List<?> list)) {
            return tags;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object key = map.get("key");
                Object value = map.get("value");
                if (key != null) {
                    tags.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
                }
            }
        }
        return tags;
    }

    @Override
    public void addServlets(@NotNull CBApplication<?> application, @NotNull DBWServletContext servletContext) throws DBException {
        if (!application.isMultiuser()) {
            return;
        }
        servletContext.addServlet(
            "fileSystems",
            new WebFSServlet(application, getServiceImpl()),
            application.getServicesURI() + "fs-data/*"
        );
    }
}
