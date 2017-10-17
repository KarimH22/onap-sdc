package org.openecomp.core.tools.store;

import com.amdocs.zusammen.datatypes.Id;
import com.amdocs.zusammen.datatypes.SessionContext;
import com.amdocs.zusammen.datatypes.item.ElementContext;
import com.amdocs.zusammen.plugin.statestore.cassandra.dao.types.ElementEntityContext;
import org.openecomp.core.zusammen.plugin.dao.types.ElementEntity;

public class ElementHandler {

    private static final String GLOBAL_USER = "GLOBAL_USER";

    public static void update(SessionContext context,
                              String itemId, String versionId, String changeRef,
                              ElementEntity elementEntity) {

        ElementEntityContext elementContext;
        CassandraElementRepository cassandraElementRepository = new CassandraElementRepository();
        if (changeRef == null) {

            elementContext = new ElementEntityContext(GLOBAL_USER, new Id(itemId),
                    new Id(versionId));

        } else {
            elementContext = new ElementEntityContext(GLOBAL_USER, new ElementContext(new Id(itemId),
                    new Id(versionId),
                    changeRef));
        }
        cassandraElementRepository.update(context, elementContext, elementEntity);

    }
}

