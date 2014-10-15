/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.grails.datastore.mapping.cassandra.engine;

import org.grails.datastore.mapping.core.impl.PendingUpdate;
import org.grails.datastore.mapping.core.impl.PendingUpdateAdapter;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.springframework.data.cassandra.core.CassandraTemplate;

import com.datastax.driver.core.Statement;

/**
 * Provides a default implementation for the {@link PendingUpdate} interface
 *
 * @param <E> The native entry to persist
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class CassandraPendingUpdateAdapter<E, K> extends PendingUpdateAdapter<E, K> implements CassandraPendingUpdate<E, K>{
	
	private Statement statement;
	private CassandraTemplate cassandraTemplate;
	
    public CassandraPendingUpdateAdapter(PersistentEntity entity, K nativeKey, Statement statement, CassandraTemplate cassandraTemplate) {
        super(entity, nativeKey, null, null);
        this.statement = statement;
        this.cassandraTemplate = cassandraTemplate;
    }

    @Override
    public void run() {    	
    	cassandraTemplate.execute(statement);
    }

	public Statement getStatement() {
		return statement;
	}

	public void setStatement(Statement statement) {
		this.statement = statement;
	}

	
}
