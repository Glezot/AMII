// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package io.unthrottled.amii.config.ui;

import com.intellij.codeInsight.intention.IntentionManager;
import com.intellij.codeInsight.intention.impl.config.IntentionActionMetaData;
import com.intellij.codeInsight.intention.impl.config.IntentionManagerImpl;
import com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.TreeExpander;
import com.intellij.ide.ui.search.SearchUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.packageDependencies.ui.TreeExpansionMonitor;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.FilterComponent;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ArrayUtil;
import com.intellij.util.TimeoutUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class PreferredCharacterTree {
  private final Map<IntentionActionMetaData, Boolean> myIntentionToCheckStatus = new HashMap<>();
  private JComponent myComponent;
  private CheckboxTree myTree;
  private FilterComponent myFilter;
  private JPanel myNorthPanel;

  protected PreferredCharacterTree() {
    initTree();
  }

  private static @NotNull List<IntentionActionMetaData> copyAndSort(@NotNull List<IntentionActionMetaData> intentionsToShow) {
    List<IntentionActionMetaData> copy = new ArrayList<>(intentionsToShow);
    copy.sort((data1, data2) -> {
      String[] category1 = data1.myCategory;
      String[] category2 = data2.myCategory;
      int result = ArrayUtil.lexicographicCompare(category1, category2);
      if (result != 0) {
        return result;
      }
      return data1.getFamily().compareTo(data2.getFamily());
    });
    return copy;
  }

  private static CheckedTreeNode findChild(TreeNode node, final String name) {
    final Ref<CheckedTreeNode> found = new Ref<>();
    visitChildren(node, node1 -> {
      String text = getNodeText(node1);
      if (name.equals(text)) {
        found.set(node1);
      }
    });
    return found.get();
  }

  private static CheckedTreeNode findChildRecursively(TreeNode node, final String name) {
    final Ref<CheckedTreeNode> found = new Ref<>();
    visitChildren(node, node1 -> {
      if (found.get() != null) return;
      final Object userObject = node1.getUserObject();
      if (userObject instanceof IntentionActionMetaData) {
        String text = getNodeText(node1);
        if (name.equals(text)) {
          found.set(node1);
        }
      } else {
        final CheckedTreeNode child = findChildRecursively(node1, name);
        if (child != null) {
          found.set(child);
        }
      }
    });
    return found.get();
  }

  private static String getNodeText(CheckedTreeNode node) {
    final Object userObject = node.getUserObject();
    String text;
    if (userObject instanceof String) {
      text = (String) userObject;
    } else if (userObject instanceof IntentionActionMetaData) {
      text = ((IntentionActionMetaData) userObject).getFamily();
    } else {
      text = "???";
    }
    return text;
  }

  private static void apply(CheckedTreeNode root) {
    Object userObject = root.getUserObject();
    if (userObject instanceof IntentionActionMetaData) {
      IntentionActionMetaData actionMetaData = (IntentionActionMetaData) userObject;
      IntentionManagerSettings.getInstance().setEnabled(actionMetaData, root.isChecked());
    } else {
      visitChildren(root, PreferredCharacterTree::apply);
    }
  }

  private static boolean isModified(CheckedTreeNode root) {
    Object userObject = root.getUserObject();
    if (userObject instanceof IntentionActionMetaData) {
      IntentionActionMetaData actionMetaData = (IntentionActionMetaData) userObject;
      boolean enabled = IntentionManagerSettings.getInstance().isEnabled(actionMetaData);
      return enabled != root.isChecked();
    } else {
      final boolean[] modified = new boolean[]{false};
      visitChildren(root, node -> modified[0] |= isModified(node));
      return modified[0];
    }
  }

  private static void visitChildren(TreeNode node, CheckedNodeVisitor visitor) {
    Enumeration<?> children = node.children();
    while (children.hasMoreElements()) {
      final CheckedTreeNode child = (CheckedTreeNode) children.nextElement();
      visitor.visit(child);
    }
  }

  public JTree getTree() {
    return myTree;
  }

  public JComponent getComponent() {
    return myComponent;
  }

  private void initTree() {
    myTree = new CheckboxTree(new CheckboxTree.CheckboxTreeCellRenderer(true) {
      @Override
      public void customizeRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        if (!(value instanceof CheckedTreeNode)) {
          return;
        }

        CheckedTreeNode node = (CheckedTreeNode) value;
        SimpleTextAttributes attributes = node.getUserObject() instanceof IntentionActionMetaData ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES;
        final String text = getNodeText(node);
        Color background = UIUtil.getTreeBackground(selected, true);
        UIUtil.changeBackGround(this, background);
        SearchUtil.appendFragments(myFilter != null ? myFilter.getFilter() : null,
          text,
          attributes.getStyle(),
          attributes.getFgColor(),
          background,
          getTextRenderer());
      }
    }, new CheckedTreeNode(null));

    myTree.getSelectionModel().addTreeSelectionListener(e -> {
      TreePath path = e.getPath();
      Object userObject = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
      selectionChanged(userObject);
    });

    myFilter = new MyFilterComponent();
    myComponent = new JPanel(new BorderLayout());
    JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(myTree);
    myNorthPanel = new JPanel(new BorderLayout());
    myNorthPanel.add(myFilter, BorderLayout.CENTER);
    myNorthPanel.setBorder(JBUI.Borders.emptyBottom(2));

    DefaultActionGroup group = new DefaultActionGroup();
    CommonActionsManager actionManager = CommonActionsManager.getInstance();

    TreeExpander treeExpander = new DefaultTreeExpander(myTree);
    group.add(actionManager.createExpandAllAction(treeExpander, myTree));
    group.add(actionManager.createCollapseAllAction(treeExpander, myTree));

    myNorthPanel.add(ActionManager.getInstance().createActionToolbar("IntentionSettingsTree", group, true).getComponent(), BorderLayout.WEST);

    myComponent.add(myNorthPanel, BorderLayout.NORTH);
    myComponent.add(scrollPane, BorderLayout.CENTER);

    myFilter.reset();
  }

  protected abstract void selectionChanged(Object selected);

  protected abstract List<IntentionActionMetaData> filterModel(String filter, final boolean force);

  public void filter(List<IntentionActionMetaData> intentionsToShow) {
    refreshCheckStatus((CheckedTreeNode) myTree.getModel().getRoot());
    reset(copyAndSort(intentionsToShow));
  }

  public void reset() {
    IntentionManagerImpl intentionManager = (IntentionManagerImpl) IntentionManager.getInstance();
    while (intentionManager.hasActiveRequests()) {
      TimeoutUtil.sleep(100);
    }

    IntentionManagerSettings intentionManagerSettings = IntentionManagerSettings.getInstance();
    myIntentionToCheckStatus.clear();
    List<IntentionActionMetaData> intentions = intentionManagerSettings.getMetaData();
    for (IntentionActionMetaData metaData : intentions) {
      myIntentionToCheckStatus.put(metaData, intentionManagerSettings.isEnabled(metaData));
    }
    reset(copyAndSort(intentions));
  }

  private void reset(@NotNull List<IntentionActionMetaData> sortedIntentions) {
    CheckedTreeNode root = new CheckedTreeNode(null);
    DefaultTreeModel treeModel = (DefaultTreeModel) myTree.getModel();
    for (IntentionActionMetaData metaData : sortedIntentions) {
      CheckedTreeNode node = root;
      for (String name : metaData.myCategory) {
        CheckedTreeNode child = findChild(node, name);
        if (child == null) {
          CheckedTreeNode newChild = new CheckedTreeNode(name);
          treeModel.insertNodeInto(newChild, node, node.getChildCount());
          child = newChild;
        }
        node = child;
      }
      treeModel.insertNodeInto(new CheckedTreeNode(metaData), node, node.getChildCount());
    }
    resetCheckMark(root);
    treeModel.setRoot(root);
    treeModel.nodeChanged(root);
    TreeUtil.expandAll(myTree);
    myTree.setSelectionRow(0);
  }

  public void selectIntention(String familyName) {
    final CheckedTreeNode child = findChildRecursively(getRoot(), familyName);
    if (child != null) {
      final TreePath path = new TreePath(child.getPath());
      TreeUtil.selectPath(myTree, path);
    }
  }

  private CheckedTreeNode getRoot() {
    return (CheckedTreeNode) myTree.getModel().getRoot();
  }

  private boolean resetCheckMark(final CheckedTreeNode root) {
    Object userObject = root.getUserObject();
    if (userObject instanceof IntentionActionMetaData) {
      IntentionActionMetaData metaData = (IntentionActionMetaData) userObject;
      Boolean b = myIntentionToCheckStatus.get(metaData);
      boolean enabled = b == Boolean.TRUE;
      root.setChecked(enabled);
      return enabled;
    } else {
      root.setChecked(false);
      visitChildren(root, node -> {
        if (resetCheckMark(node)) {
          root.setChecked(true);
        }
      });
      return root.isChecked();
    }
  }

  public void apply() {
    CheckedTreeNode root = getRoot();
    apply(root);
  }

  private void refreshCheckStatus(final CheckedTreeNode root) {
    Object userObject = root.getUserObject();
    if (userObject instanceof IntentionActionMetaData) {
      IntentionActionMetaData actionMetaData = (IntentionActionMetaData) userObject;
      myIntentionToCheckStatus.put(actionMetaData, root.isChecked());
    } else {
      visitChildren(root, this::refreshCheckStatus);
    }

  }

  public boolean isModified() {
    return isModified(getRoot());
  }

  public void dispose() {
    myFilter.dispose();
  }

  public String getFilter() {
    return myFilter.getFilter();
  }

  public void setFilter(String filter) {
    myFilter.setFilter(filter);
  }

  public JPanel getToolbarPanel() {
    return myNorthPanel;
  }

  interface CheckedNodeVisitor {
    void visit(CheckedTreeNode node);
  }

  private class MyFilterComponent extends FilterComponent {
    private final TreeExpansionMonitor<DefaultMutableTreeNode> myExpansionMonitor = TreeExpansionMonitor.install(myTree);

    MyFilterComponent() {
      super("INTENTION_FILTER_HISTORY", 10);
    }

    @Override
    public void filter() {
      final String filter = getFilter();
      if (filter != null && filter.length() > 0) {
        if (!myExpansionMonitor.isFreeze()) {
          myExpansionMonitor.freeze();
        }
      }
      PreferredCharacterTree.this.filter(filterModel(filter, true));
      if (myTree != null) {
        List<TreePath> expandedPaths = TreeUtil.collectExpandedPaths(myTree);
        ((DefaultTreeModel) myTree.getModel()).reload();
        TreeUtil.restoreExpandedPaths(myTree, expandedPaths);
      }
      SwingUtilities.invokeLater(() -> {
        myTree.setSelectionRow(0);
        IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown(() -> IdeFocusManager.getGlobalInstance().requestFocus(myTree, true));
      });
      TreeUtil.expandAll(myTree);
      if (filter == null || filter.length() == 0) {
        TreeUtil.collapseAll(myTree, 0);
        myExpansionMonitor.restore();
      }
    }

    @Override
    protected void onlineFilter() {
      final String filter = getFilter();
      if (filter != null && filter.length() > 0) {
        if (!myExpansionMonitor.isFreeze()) {
          myExpansionMonitor.freeze();
        }
      }
      PreferredCharacterTree.this.filter(filterModel(filter, true));
      TreeUtil.expandAll(myTree);
      if (filter == null || filter.length() == 0) {
        TreeUtil.collapseAll(myTree, 0);
        myExpansionMonitor.restore();
      }
    }
  }
}