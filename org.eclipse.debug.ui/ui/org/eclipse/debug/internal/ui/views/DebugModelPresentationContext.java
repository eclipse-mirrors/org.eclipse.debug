/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.internal.ui.views;

import org.eclipse.debug.internal.ui.viewers.PresentationContext;
import org.eclipse.debug.ui.IDebugModelPresentation;

/**
 * A presentation context that has a debug model presentation.
 * 
 * @since 3.3
 */
public class DebugModelPresentationContext extends PresentationContext {

	private IDebugModelPresentation fPresentation;
	
	/**
	 * Constructs a presentation context for the given id using the
	 * specified model presentation.
	 * 
	 * @param id context id
	 * @param presentation debug model presentation
	 */
	public DebugModelPresentationContext(String id, IDebugModelPresentation presentation) {
		super(id);
		fPresentation = presentation;
	}
	
	public IDebugModelPresentation getModelPresentation() {
		return fPresentation;
	}

}