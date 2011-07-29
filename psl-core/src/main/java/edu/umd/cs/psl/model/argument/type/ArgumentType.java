/*
 * This file is part of the PSL software.
 * Copyright 2011 University of Maryland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.umd.cs.psl.model.argument.type;

public interface ArgumentType {
	
	public boolean isAttribute();
	
	public boolean isEntity();
	
	/**
	 * Returns true of this {@link ArgumentType} is a sub-type of the given ArgumentType t.
	 * 
	 * Currently, PSL does not properly support type hierarchies and so this
	 * function merely checks for equality.
	 * @param t ArgumentType to compare with
	 * @return TRUE, if t is a more general or equal type, else FALSE
	 */
	public boolean isSubTypeOf(ArgumentType t);
	
	
	public String getName();
	
}
