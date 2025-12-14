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

/**
 * A Pokemon entity class. A Pokemon is represented as a triple of an
 * ID, a name and a type.
 *
 * Pokemon, and Pokemon character names are trademarks of Nintendo.
 */
public class Pokemon {

    private int id;

    private String name;

    private PokemonType pokemonType;

    private int type;

    public Pokemon() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PokemonType getPokemonType() {
        return pokemonType;
    }

    public void setPokemonType(PokemonType pokemonType) {
        this.pokemonType = pokemonType;
        this.type = pokemonType.getId();
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }
}
