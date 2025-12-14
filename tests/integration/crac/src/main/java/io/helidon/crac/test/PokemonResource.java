/*
 * Copyright (c) 2025 Oracle and/or its affiliates.
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
package io.helidon.crac.test;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * This class implements REST endpoints to interact with Pokemons. The following
 * operations are supported:
 *
 * <ul>
 * <li>GET /pokemon: Retrieve list of all pokemons</li>
 * <li>GET /pokemon/{id}: Retrieve single pokemon by ID</li>
 * <li>GET /pokemon/name/{name}: Retrieve single pokemon by name</li>
 * <li>DELETE /pokemon/{id}: Delete a pokemon by ID</li>
 * <li>POST /pokemon: Create a new pokemon</li>
 * </ul>
 *
 * Pokemon, and Pokemon character names are trademarks of Nintendo.
 */
@Path("pokemon")
public class PokemonResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Pokemon> getPokemons() {
        return List.of();
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Pokemon getPokemonById(@PathParam("id") String id) {
        throw new NotFoundException("Unable to find pokemon with ID " + id);
    }

    @DELETE
    @Path("{id}")
    public void deletePokemon(@PathParam("id") String id) {
    }

    @GET
    @Path("name/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Pokemon getPokemonByName(@PathParam("name") String name) {
        throw new NotFoundException("Unable to find pokemon with name " + name);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createPokemon(Pokemon pokemon) {
    }
}
