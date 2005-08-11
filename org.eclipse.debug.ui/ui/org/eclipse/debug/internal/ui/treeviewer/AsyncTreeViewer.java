/*******************************************************************************
 * Copyright (c) 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.internal.ui.treeviewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.TreeEvent;
import org.eclipse.swt.events.TreeListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

/**
 * TODO: sorting/filtering should be implemented above content viewer TODO: tree
 * editor not implemented
 * 
 * TODO: variables viewer (dup elements) TODO: color and font support
 * 
 * TODO: default implementation of getting the image must run in UI thread, but
 * other implementations could run in non-UI thread
 * 
 * TODO: default presentation adapter should use deferred workbench adapters for
 * backwards compatibility
 */
public class AsyncTreeViewer extends StructuredViewer {

	/**
	 * A map of elements to associated tree items or tree
	 */
	Map fElementsToWidgets = new HashMap();

	/**
	 * A map of widget to parent widgets used to avoid requirement for parent
	 * access in UI thread. Currently used by update objects to detect/cancel
	 * updates on updates of children.
	 */
	Map fItemToParentItem = new HashMap();

	/**
	 * Map of widgets to their data elements used to avoid requirement to access
	 * data in UI thread.
	 */
	Map fWidgetsToElements = new HashMap();

	List fPendingUpdates = new ArrayList();

	Map fImageCache = new HashMap();

	Tree fTree;

	Object fInput;

	IPresentationContext fContext;

	TreeSelection fPendingSelection;

	TreeSelection fCurrentSelection;

	TreePath[] fPendingExpansion;

	/**
	 * Creates an asynchronous tree viewer on a newly-created tree control under
	 * the given parent. The tree control is created using the SWT style bits
	 * <code>MULTI, H_SCROLL, V_SCROLL,</code> and <code>BORDER</code>. The
	 * viewer has no input, no content provider, a default label provider, no
	 * sorter, and no filters.
	 * 
	 * @param parent
	 *            the parent control
	 */
	public AsyncTreeViewer(Composite parent) {
		this(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
	}

	/**
	 * Creates an asynchronous tree viewer on a newly-created tree control under
	 * the given parent. The tree control is created using the given SWT style
	 * bits. The viewer has no input.
	 * 
	 * @param parent
	 *            the parent control
	 * @param style
	 *            the SWT style bits used to create the tree.
	 */
	public AsyncTreeViewer(Composite parent, int style) {
		this(new Tree(parent, style));
	}

	/**
	 * Creates an asynchronous tree viewer on the given tree control. The viewer
	 * has no input, no content provider, a default label provider, no sorter,
	 * and no filters.
	 * 
	 * @param tree
	 *            the tree control
	 */
	public AsyncTreeViewer(Tree tree) {
		super();
		fTree = tree;
		hookControl(fTree);
		setUseHashlookup(false);
		setContentProvider(new NullContentProvider());
		tree.addTreeListener(new TreeListener() {
			public void treeExpanded(TreeEvent e) {
				Object element = e.item.getData();
				Widget[] items = getWidgets(element);
				if (items == null) {
					return;
				}
				update(element);
				for (int i = 0; i < items.length; i++) {
					Widget item = items[i];
					updateChildren(element, item);
				}
			}

			public void treeCollapsed(TreeEvent e) {
			}
		});
	}

	protected void dispose() {
		Iterator images = fImageCache.values().iterator();
		while (images.hasNext()) {
			Image image = (Image) images.next();
			image.dispose();
		}
		fElementsToWidgets.clear();
		fPendingUpdates.clear();
	}

	/**
	 * Updates all occurrences of the given element in this tree.
	 * 
	 * @param element
	 */
	public void update(Object element) {
		if (element == fInput) {
			return;
		}
		IPresentationAdapter adapter = getPresentationAdapter(element);
		if (adapter != null) {
			Widget[] items = getWidgets(element);
			if (items != null) {
				for (int i = 0; i < items.length; i++) {
					TreeItem item = (TreeItem) items[i];
					ILabelUpdate labelUpdate = new LabelUpdate(item, this);
					schedule(labelUpdate);
					adapter.retrieveLabel(element, fContext, labelUpdate);
				}
			}
		}
	}

	/**
	 * Refreshes all occurrences of the given element in this tree, and visible
	 * children.
	 * 
	 * @param element
	 */
	public void refresh(Object element) {
		internalRefresh(element);
	}

	protected void updateChildren(Object parent, Widget item) {
		IPresentationAdapter adapter = getPresentationAdapter(parent);
		if (adapter != null) {
			IChildrenUpdate updateChildren = new ChildrenUpdate(item, this);
			schedule(updateChildren);
			adapter.retrieveChildren(parent, fContext, updateChildren);
		}
	}

	protected IPresentationAdapter getPresentationAdapter(Object element) {
		IPresentationAdapter adapter = null;
		if (element instanceof IAdaptable) {
			IAdaptable adaptable = (IAdaptable) element;
			adapter = (IPresentationAdapter) adaptable.getAdapter(IPresentationAdapter.class);
		}
		return adapter;
	}

	/**
	 * Cancels any conflicting updates for children of the given item, and
	 * schedules the new update.
	 * 
	 * @param update
	 */
	protected void schedule(IPresentationUpdate update) {
		AbstractUpdate absUpdate = (AbstractUpdate) update;
		synchronized (fPendingUpdates) {
			Iterator updates = fPendingUpdates.listIterator();
			while (updates.hasNext()) {
				AbstractUpdate pendingUpdate = (AbstractUpdate) updates.next();
				if (absUpdate.contains(pendingUpdate)) {
					pendingUpdate.setCanceled(true);
					updates.remove();
				}
			}
			fPendingUpdates.add(update);
		}
	}

	/**
	 * Returns the widgets associated with the given element or
	 * <code>null</code>.
	 * 
	 * @param element
	 * @return
	 */
	protected synchronized Widget[] getWidgets(Object element) {
		if (element == null) {
			return null;
		}
		return (Widget[]) fElementsToWidgets.get(element);
	}

	/**
	 * Expands all elements in the given tree selection.
	 * 
	 * @param selection
	 */
	public synchronized void expand(ISelection selection) {
		if (selection instanceof TreeSelection) {
			fPendingExpansion = ((TreeSelection) selection).getPaths();
			attemptExpansion();
		}
	}

	synchronized void attemptExpansion() {
		if (fPendingExpansion != null) {
			for (int i = 0; i < fPendingExpansion.length; i++) {
				TreePath path = fPendingExpansion[i];
				if (path != null && attemptExpansion(path)) {
					fPendingExpansion[i] = null;
				}
			}
		}
	}

	synchronized boolean attemptExpansion(TreePath path) {
		int segmentCount = path.getSegmentCount();
		boolean pathFound = false;
		for (int j = segmentCount - 1; j >= 0 && !pathFound; j--) {
			Object element = path.getSegment(j);
			Widget[] treeItems = (Widget[]) fElementsToWidgets.get(element);
			if (treeItems != null) {
				for (int k = 0; k < treeItems.length; k++) {
					if (treeItems[k] instanceof TreeItem) {
						TreeItem treeItem = (TreeItem) treeItems[k];
						TreePath treePath = getTreePath(treeItem);
						if (path.includes(treePath)) {
							if (!treeItem.getExpanded()) {
								treeItem.setExpanded(true);
								update(element);
								updateChildren(element, treeItem);

								if (j == segmentCount - 1) {
									return true;
								}
								return false;
							}
						}
					} else if (treeItems[k] instanceof Tree) {
						Tree tree = (Tree) treeItems[k];
						TreeItem[] items = tree.getItems();
						for (int i = 0; i < items.length; i++) {
							TreeItem item = items[i];
							item.setExpanded(true);
						}
						return false;
					}
				}
			}
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.viewers.Viewer#getControl()
	 */
	public Control getControl() {
		return fTree;
	}

	protected synchronized void unmapAllElements() {
		Iterator iterator = fElementsToWidgets.keySet().iterator();
		while (iterator.hasNext()) {
			Object element = iterator.next();
			Widget[] widgets = getWidgets(element);
			if (widgets != null) {
				for (int i = 0; i < widgets.length; i++) {
					Widget widget = widgets[i];
					if (widget instanceof TreeItem) {
						TreeItem item = (TreeItem) widget;
						item.dispose();
					}
				}
			}
		}
		fElementsToWidgets.clear();
		fItemToParentItem.clear();
		fWidgetsToElements.clear();
	}

	protected synchronized void cancelPendingUpdates() {
		Iterator updates = fPendingUpdates.iterator();
		while (updates.hasNext()) {
			IPresentationUpdate update = (IPresentationUpdate) updates.next();
			update.setCanceled(true);
		}
		fPendingUpdates.clear();
	}

	/**
	 * Internal hook method called when the input to this viewer is initially
	 * set or subsequently changed.
	 * <p>
	 * The default implementation does nothing. Subclassers may override this
	 * method to do something when a viewer's input is set. A typical use is
	 * populate the viewer.
	 * </p>
	 * 
	 * @param input
	 *            the new input of this viewer, or <code>null</code> if none
	 * @param oldInput
	 *            the old input element or <code>null</code> if there was
	 *            previously no input
	 */
	protected void inputChanged(Object input, Object oldInput) {
		cancelPendingUpdates();
		unmapAllElements();
		fInput = input;

		map(input, fTree);
		refresh();
	}

	/**
	 * Maps the given element to the given item.
	 * 
	 * @param element
	 * @param item
	 *            TreeItem or Tree
	 */
	protected void map(Object element, Widget item) {
		item.setData(element);
		Object object = fElementsToWidgets.get(element);
		fWidgetsToElements.put(item, element);
		if (object == null) {
			fElementsToWidgets.put(element, new Widget[] { item });
		} else {
			Widget[] old = (Widget[]) object;
			Widget[] items = new Widget[old.length + 1];
			System.arraycopy(old, 0, items, 0, old.length);
			items[old.length] = item;
			fElementsToWidgets.put(element, items);
		}
		if (item instanceof TreeItem) {
			TreeItem treeItem = (TreeItem) item;
			TreeItem parentItem = treeItem.getParentItem();
			if (parentItem != null) {
				fItemToParentItem.put(treeItem, parentItem);
			}
		}
	}

	/**
	 * Returns all paths to the given element or <code>null</code> if none.
	 * 
	 * @param element
	 * @return paths to the given element or <code>null</code>
	 */
	public synchronized TreePath[] getTreePaths(Object element) {
		Widget[] widgets = getWidgets(element);
		if (widgets == null) {
			return null;
		}
		TreePath[] paths = new TreePath[widgets.length];
		for (int i = 0; i < widgets.length; i++) {
			List path = new ArrayList();
			path.add(element);
			Widget widget = widgets[i];
			TreeItem parent = null;
			if (widget instanceof TreeItem) {
				TreeItem treeItem = (TreeItem) widget;
				parent = getParentItem(treeItem);
			}
			while (parent != null) {
				Object data = fWidgetsToElements.get(parent);
				path.add(0, data);
				parent = getParentItem(parent);
			}
			path.add(0, fInput);
			paths[i] = new TreePath(path.toArray());
			if (widget instanceof TreeItem) {
				paths[i].setTreeItem((TreeItem) widget);
			}
		}
		return paths;
	}

	protected TreePath getTreePath(TreeItem item) {
		TreeItem parent = item;
		List path = new ArrayList();
		while (parent != null) {
			path.add(0, parent.getData());
			parent = parent.getParentItem();
		}
		path.add(0, fTree.getData());
		return new TreePath(path.toArray());
	}

	/**
	 * Removes the update from the pending updates list.
	 * 
	 * @param update
	 */
	void updateComplete(AbstractUpdate update) {
		synchronized (fPendingUpdates) {
			fPendingUpdates.remove(update);
		}
	}

	synchronized void setChildren(Widget widget, List children, List hasChildren) {
		TreeItem[] oldItems = null;
		if (widget instanceof Tree) {
			Tree tree = (Tree) widget;
			oldItems = tree.getItems();
		} else {
			oldItems = ((TreeItem) widget).getItems();
		}
		Iterator newKids = children.iterator();
		int index = 0;
		while (newKids.hasNext()) {
			Object kid = newKids.next();
			boolean hasKids = ((Boolean) hasChildren.get(index)).booleanValue();
			if (index < oldItems.length) {
				TreeItem oldItem = oldItems[index];
				Object oldData = oldItem.getData();
				if (!kid.equals(oldData)) {
					unmap(oldData, oldItem);
					map(kid, oldItem);
				}
				if (!hasKids && oldItem.getItemCount() > 0) {
					// dispose children
					TreeItem[] items = oldItem.getItems();
					for (int i = 0; i < items.length; i++) {
						TreeItem oldChild = items[i];
						unmap(oldChild.getData(), oldChild);
						oldChild.dispose();
					}
				} else if (hasKids && oldItem.getItemCount() == 0) {
					// dummy to update +
					new TreeItem(oldItem, SWT.NONE);
				}
			} else {
				TreeItem newItem = newTreeItem(widget, index);
				map(kid, newItem);
				if (hasKids) {
					// dummy to update +
					new TreeItem(newItem, SWT.NONE);
				}
			}
			index++;
		}
		// remove left over old items
		while (index < oldItems.length) {
			TreeItem oldItem = oldItems[index];
			unmap(oldItem.getData(), oldItem);
			oldItem.dispose();
			index++;
		}
		// refresh the current kids
		newKids = children.iterator();
		while (newKids.hasNext()) {
			refresh(newKids.next());
		}
		attemptExpansion();
		attemptSelection(true);
	}

	protected TreeItem newTreeItem(Widget parent, int index) {
		if (parent instanceof Tree) {
			return new TreeItem((Tree) parent, SWT.NONE, index);
		}
		return new TreeItem((TreeItem) parent, SWT.NONE, index);
	}

	/**
	 * Unmaps the given item, and unmaps and disposes of all children of that
	 * item. Does not dispose of the given item.
	 * 
	 * @param kid
	 * @param oldItem
	 */
	protected synchronized void unmap(Object kid, TreeItem oldItem) {
		if (kid == null) {
			// when unmapping a dummy item
			return;
		}
		Widget[] widgets = (Widget[]) fElementsToWidgets.get(kid);
		fWidgetsToElements.remove(oldItem);
		if (widgets != null) {
			for (int i = 0; i < widgets.length; i++) {
				Widget item = widgets[i];
				if (item == oldItem) {
					fItemToParentItem.remove(item);
					if (widgets.length == 1) {
						fElementsToWidgets.remove(kid);
					} else {
						Widget[] newItems = new Widget[widgets.length - 1];
						System.arraycopy(widgets, 0, newItems, 0, i);
						if (i < newItems.length) {
							System.arraycopy(widgets, i + 1, newItems, i, newItems.length - i);
						}
						fElementsToWidgets.put(kid, newItems);
					}
				}
			}
		}
		TreeItem[] children = oldItem.getItems();
		for (int i = 0; i < children.length; i++) {
			TreeItem child = children[i];
			unmap(child.getData(), child);
			child.dispose();
		}
	}

	protected Image getImage(ImageDescriptor descriptor) {
		Image image = (Image) fImageCache.get(descriptor);
		if (image == null) {
			image = new Image(getControl().getDisplay(), descriptor.getImageData());
			fImageCache.put(descriptor, image);
		}
		return image;
	}

	public void setContext(IPresentationContext context) {
		fContext = context;
	}

	/**
	 * Returns the parent item for an item or <code>null</code> if none.
	 * 
	 * @param item
	 *            item for which parent is requested
	 * @return parent item or <code>null</code>
	 */
	protected synchronized TreeItem getParentItem(TreeItem item) {
		return (TreeItem) fItemToParentItem.get(item);
	}

	protected Widget doFindInputItem(Object element) {
		if (element.equals(fInput)) {
			return fTree;
		}
		return null;
	}

	protected Widget doFindItem(Object element) {
		Widget[] widgets = getWidgets(element);
		if (widgets != null && widgets.length > 0) {
			return widgets[0];
		}
		return null;
	}

	protected void doUpdateItem(Widget item, Object element, boolean fullMap) {
		update(element);
	}

	public ISelection getSelection() {
		Control control = getControl();
		if (control == null || control.isDisposed()) {
			return StructuredSelection.EMPTY;
		}
		List list = getSelectionFromWidget();
		return new TreeSelection((TreePath[]) list.toArray());
	}

	protected List getSelectionFromWidget() {
		TreeItem[] selection = fTree.getSelection();
		TreePath[] paths = new TreePath[selection.length];
		for (int i = 0; i < selection.length; i++) {
			paths[i] = getTreePath(selection[i]);
		}
		return Arrays.asList(paths);
	}

	protected void internalRefresh(Object element) {
		Widget[] items = getWidgets(element);
		if (items == null) {
			return;
		}
		update(element);
		for (int i = 0; i < items.length; i++) {
			Widget item = items[i];
			if (element == fInput) {
				updateChildren(element, item);
			} else if (((TreeItem) item).getExpanded()) {
				updateChildren(element, item);
			}
		}
	}

	public void reveal(Object element) {
		Widget[] widgets = getWidgets(element);
		if (widgets != null && widgets.length > 0) {
			TreeItem item = (TreeItem) widgets[0];
			Tree tree = (Tree) getControl();
			tree.showItem(item);
		}
	}

	protected void setSelectionToWidget(ISelection selection, boolean reveal) {
		if (selection instanceof TreeSelection) {
			setSelectionToWidget((TreeSelection) selection, reveal);
		} else {
			super.setSelectionToWidget(selection, reveal);
		}
	}

	protected void setSelectionToWidget(List list, boolean reveal) {
		for (Iterator iter = list.iterator(); iter.hasNext();) {
			Object selection = iter.next();
			if (selection instanceof TreeSelection) {
				setSelectionToWidget((TreeSelection) selection, reveal);
			}
		}
	}

	protected void setSelectionToWidget(TreeSelection selection, final boolean reveal) {
		// check if same
		if (fCurrentSelection != null) {
			if (fCurrentSelection.equals(selection) && selection.equals(getSelection())) {
				return;
			}
			fCurrentSelection = null;
		}
		fPendingSelection = selection;
		fTree.getDisplay().asyncExec(new Runnable() {
			public void run() {
				attemptSelection(reveal);
			}
		});
		
	}

	synchronized void attemptSelection(final boolean reveal) {
		if (fPendingSelection != null) {
			List toSelect = new ArrayList();
			TreePath[] paths = fPendingSelection.getPaths();
			if (paths == null) {
				return;
			}
			int selections = 0;
			for (int i = 0; i < paths.length; i++) {
				TreePath path = paths[i];
				TreePath[] treePaths = getTreePaths(path.getLastSegment());
				if (treePaths != null) {
					for (int j = 0; j < treePaths.length; j++) {
						TreePath existingPath = treePaths[j];
						if (existingPath.equals(path)) {
							toSelect.add(existingPath.getTreeItem());
							selections++;
							break;
						}
					}
				}
			}
			if (selections == paths.length) {
				// done
				fPendingSelection = null;
			}
			if (!toSelect.isEmpty()) {
				final TreeItem[] items = (TreeItem[]) toSelect.toArray(new TreeItem[toSelect.size()]);

				fTree.setSelection(items);
				if (reveal) {
					fTree.showItem(items[0]);
				}
				fCurrentSelection = (TreeSelection) getSelection();
				fireSelectionChanged(new SelectionChangedEvent(AsyncTreeViewer.this, fCurrentSelection));
			}
		}
	}

	private class NullContentProvider implements IStructuredContentProvider {
		public void dispose() {
		}

		public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		}

		public Object[] getElements(Object inputElement) {
			return null;
		}
	}
}
