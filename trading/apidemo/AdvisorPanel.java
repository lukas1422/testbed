/* Copyright (C) 2023 Interactive Brokers LLC. All rights reserved. This code is subject to the terms
 * and conditions of the IB API Non-Commercial License or the IB API Commercial License, as applicable. */

package apidemo;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;

import client.Types.FADataType;
import client.Types.Method;
import controller.Alias;
import controller.ApiController.IAdvisorHandler;
import controller.Group;

import apidemo.util.HtmlButton;
import apidemo.util.NewTabbedPanel.NewTabPanel;
import apidemo.util.TCombo;
import apidemo.util.VerticalPanel;

public class AdvisorPanel extends NewTabPanel implements IAdvisorHandler {
	static DefaultCellEditor DEF_CELL_EDITOR = new DefaultCellEditor( new JTextField() );
	static {
		DEF_CELL_EDITOR.setClickCountToStart( 1);
	}
	
	private final GroupModel m_groupModel = new GroupModel();
	private final AliasModel m_aliasModel = new AliasModel();
	
	private final JTable m_groupTable = new JTable( m_groupModel) {
		public TableCellEditor getCellEditor(int row, int col) {
			return m_groupModel.getCellEditor(row, col);
		}
	};

	AdvisorPanel() {
		JPanel mainPanel = new JPanel();
		mainPanel.setLayout( new BoxLayout( mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBorder( new EmptyBorder( 0, 10, 0, 0) );
		mainPanel.add( new GroupsPanel() );

		JScrollPane aliasScroll = new JScrollPane(new JTable( m_aliasModel));
		aliasScroll.setBorder( new TitledBorder( "Aliases"));
		aliasScroll.setPreferredSize(new Dimension( 300, 2000));
		
		setLayout( new BorderLayout() );
		add( aliasScroll, BorderLayout.WEST);
		add( mainPanel);
	}

	/** Called when the tab is first visited. */
	@Override public void activated() {
		ApiDemo.INSTANCE.controller().reqAdvisorData( FADataType.GROUPS, this);
		ApiDemo.INSTANCE.controller().reqAdvisorData( FADataType.ALIASES, this );
	}
	
	/** Called when the tab is closed by clicking the X. */
	@Override public void closed() {
	}

	@Override public void groups(List<Group> groups) {
		m_groupModel.update( groups);
	}

	@Override public void aliases(List<Alias> aliases) {
		m_aliasModel.update( aliases);
	}
	
	@Override public void updateGroupsEnd(String text) {
		JOptionPane.showMessageDialog(this, "The groups have been updated: " + text);
	}

	private static class AliasModel extends AbstractTableModel {
		List<Alias> m_list = new ArrayList<>();
		
		@Override public int getRowCount() {
			return m_list.size();
		}

		public void update(List<Alias> aliases) {
			m_list.clear();
			m_list.addAll( aliases);
			fireTableDataChanged();
		}

		@Override public int getColumnCount() {
			return 2;
		}

		@Override public String getColumnName(int col) {
			switch( col) {
				case 0: return "Account";
				case 1: return "Alias";
				default: return null;
			}
		}
		
		@Override public Object getValueAt(int rowIn, int col) {
			Alias row = m_list.get( rowIn);
			switch( col) {
				case 0: return row.account();
				case 1: return row.alias();
				default: return null;
			}
		}
	}
	
	private class GroupsPanel extends JPanel {
		GroupsPanel() {
			JScrollPane groupScroll = new JScrollPane( m_groupTable);
			groupScroll.setBorder( new TitledBorder( "Groups"));
			
			HtmlButton create = new HtmlButton( "Create Group") {
				@Override protected void actionPerformed() {
					onCreateGroup();
				}
			};

			HtmlButton update = new HtmlButton( "Update") {
				@Override protected void actionPerformed() {
					onTransmit();
				}
			};
			
			JPanel buts = new VerticalPanel();
			buts.add( create);
			buts.add( update);

			setLayout( new BorderLayout() );
			add( groupScroll);
			add( buts, BorderLayout.EAST);
		}

		void onCreateGroup() {
			String name = JOptionPane.showInputDialog( this, "Enter group name");
			if (name != null) {
				m_groupModel.add( name);
			}
		}

		void onTransmit() {
			int rc = JOptionPane.showConfirmDialog( this, "This will replace all Groups in TWS with the ones shown here.\nAre you sure you want to do that?", "Confirm", JOptionPane.YES_NO_OPTION);
			if (rc == 0) {
				m_groupModel.transmit();
			}
		}
	}
	
	private static class GroupModel extends AbstractTableModel {
		TCombo<Method> combo = new TCombo<>(Method.values());
		DefaultCellEditor EDITOR = new DefaultCellEditor( combo);
		List<Group> m_groups = new ArrayList<>();
		
		GroupModel() {		
			EDITOR.setClickCountToStart( 1);
		}
		
		void update(List<Group> groups) {
			m_groups.clear();
			m_groups.addAll( groups);
			fireTableDataChanged();
		}

		void add(String name) {
			Group group = new Group();
			group.name( name);
			m_groups.add( group);
			fireTableDataChanged();
		}

		public void transmit() {
			ApiDemo.INSTANCE.controller().updateGroups(m_groups);
		}

		@Override public int getRowCount() {
			return m_groups.size();
		}

		@Override public int getColumnCount() {
			return 5;
		}

		@Override public String getColumnName(int col) {
			switch( col) {
				case 0: return "Name";
				case 1: return "Default Method";
				case 2: return "Default Size";
				case 3: return "Risk Criteria";
				case 4: return "Accounts (acc1,amount1;acct2,amount2;...)";
				default: return null;
			}
		}
		
		@Override public Object getValueAt(int rowIn, int col) {
			Group row = m_groups.get( rowIn);
			switch( col) {
				case 0: return row.name();
				case 1: return row.defaultMethod();
				case 2: return row.defaultSize();
				case 3: return row.riskCriteria();
				case 4: return row.getAllAccounts();
				default: return null;
			}
		}
		
		@Override public boolean isCellEditable(int rowIndex, int col) {
			return true;
		}
		
		TableCellEditor getCellEditor(int row, int col) {
			return col == 1 ? EDITOR : DEF_CELL_EDITOR;
		}
		
		@Override public void setValueAt(Object val, int rowIn, int col) {
			Group row = m_groups.get( rowIn);
			switch( col) {
				case 0: row.name( (String)val); break;
				case 1: row.defaultMethod( (Method)val); break;
				case 2: row.defaultSize( (String)val); break;
				case 3: row.riskCriteria( (String)val); break;
				case 4: row.setAllAccounts( (String)val); break;
				default: break;
			}
		}
	}
}
