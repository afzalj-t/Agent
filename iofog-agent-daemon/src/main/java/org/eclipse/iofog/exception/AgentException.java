/*******************************************************************************
 * Copyright (c) 2019 Edgeworx, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Saeid Baghbidi
 * Kilton Hopkins
 * Neha Naithani
 *******************************************************************************/
package org.eclipse.iofog.exception;

/**
 * Agent Exception
 * @author nehanaithani
 *
 */
public class AgentException extends Exception{
	
	final Exception innerException;
	final String message;
	
	private static final long serialVersionUID = 1L;
    
	public AgentException(String message, Exception innerException) {
		this.message = message;
		this.innerException = innerException;
	}

}
