// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.graphql;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.openqa.selenium.grid.distributor.Distributor;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static org.openqa.selenium.json.Json.JSON_UTF_8;
import static org.openqa.selenium.remote.http.Contents.utf8String;

public class GraphqlHandler implements HttpHandler {

  public static final String GRID_SCHEMA = "/org/openqa/selenium/grid/graphql/selenium-grid-schema.graphqls";
  public static final Json JSON = new Json();
  private final Distributor distributor;
  private final String publicUrl;
  private final GraphQL graphQl;

  public GraphqlHandler(Distributor distributor, String publicUrl) {
    this.distributor = Objects.requireNonNull(distributor);
    this.publicUrl = Objects.requireNonNull(publicUrl);

    GraphQLSchema schema = new SchemaGenerator()
      .makeExecutableSchema(buildTypeDefinitionRegistry(), buildRuntimeWiring());

    Cache<String, PreparsedDocumentEntry> cache = CacheBuilder.newBuilder()
      .maximumSize(1024)
      .build();

    graphQl = GraphQL.newGraphQL(schema)
      .preparsedDocumentProvider((executionInput, computeFunction) -> {
        try {
          return cache.get(executionInput.getQuery(), () -> computeFunction.apply(executionInput));
        } catch (ExecutionException e) {
          if (e.getCause() instanceof RuntimeException) {
            throw (RuntimeException) e.getCause();
          } else if (e.getCause() != null) {
            throw new RuntimeException(e.getCause());
          }
          throw new RuntimeException(e);
        }
      })
      .build();
  }

  @Override
  public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
    ExecutionInput executionInput = ExecutionInput.newExecutionInput(Contents.string(req))
      .build();

    ExecutionResult result = graphQl.execute(executionInput);

    if (result.isDataPresent()) {
      return new HttpResponse()
        .addHeader("Content-Type", JSON_UTF_8)
        .setContent(utf8String(JSON.toJson(result.toSpecification())));
    }

    return new HttpResponse()
      .setStatus(HTTP_INTERNAL_ERROR)
      .setContent(utf8String(JSON.toJson(result.getErrors())));
  }

  private RuntimeWiring buildRuntimeWiring() {
    return RuntimeWiring.newRuntimeWiring()
      .scalar(Types.Uri)
      .scalar(Types.Url)
      .type("GridQuery", typeWiring -> typeWiring
        .dataFetcher("grid", new GridData(distributor, publicUrl)))
      .build();
  }

  private TypeDefinitionRegistry buildTypeDefinitionRegistry() {
    try (InputStream stream = getClass().getResourceAsStream(GRID_SCHEMA)) {
      return new SchemaParser().parse(stream);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
