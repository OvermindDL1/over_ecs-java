package com.overminddl1.over_ecs;

import com.overminddl1.over_ecs.archetypes.Archetype;
import com.overminddl1.over_ecs.archetypes.ArchetypeSwapRemoveResult;
import com.overminddl1.over_ecs.bundles.Bundle;
import com.overminddl1.over_ecs.bundles.BundleFactory;
import com.overminddl1.over_ecs.bundles.BundleInfo;
import com.overminddl1.over_ecs.bundles.BundleInserter;
import com.overminddl1.over_ecs.components.ComponentInfo;
import com.overminddl1.over_ecs.entities.EntityLocation;
import com.overminddl1.over_ecs.storages.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class Entity {
	private static final HashMap<Class<? extends Component>, Integer> single_id_map = new HashMap<>();
	private static final EntitySingleBundleFactory single_bf = new EntitySingleBundleFactory();
	private static final EntitySingleBundle single_b = new EntitySingleBundle();
	private final World world;
	private final long entity;
	private EntityLocation location;

	public Entity(World world, long entity, EntityLocation location) {
		this.world = world;
		this.entity = entity;
		this.location = location;
	}

	public static long init(int generation, int id) {
		return ((long) generation << 32) | id;
	}

	public static int id(long entity) {
		return (int) entity; //(entity & 0xFFFFFFFF); // TODO(EntityShape): Change this if we change from `long`
	}

	public static int generation(long entity) {
		return (int) ((entity >> 32)); //((entity >> 32) & 0xFFFFFFFF); // TODO(EntityShape): Change this if we change from `long`
	}

	private static void move_entity_from_remove(Entity entity, EntityLocation location, int archetype_id, EntityLocation old_location, Entities entities, Archetypes archetypes, Storages storages, Integer new_archetype_id, boolean drop) {
		Archetype old_archetype = archetypes.get(archetype_id);
		ArchetypeSwapRemoveResult remove_result = old_archetype.swap_remove(old_location.index);
		if (remove_result.swapped_entity != null) {
			entities.getMeta(remove_result.swapped_entity).location = old_location;
		}
		int old_table_row = remove_result.table_row;
		int old_table_id = old_archetype.getTableId();
		Archetype new_archetype = archetypes.get(new_archetype_id);
		EntityLocation new_location;
		if (old_table_id == new_archetype.getTableId()) {
			new_location = new_archetype.allocate(entity.entity, old_table_row);
		} else {
			Table old_table = storages.tables.get(old_table_id);
			Table new_table = storages.tables.get(new_archetype.getTableId());
			TableMoveResult move_result = new TableMoveResult();
			if (drop) {
				old_table.move_to_and_drop_missing(old_table_row, new_table, move_result);
			} else {
				old_table.move_to_and_forget_missing(old_table_row, new_table, move_result);
			}
			new_location = new_archetype.allocate(entity.entity, move_result.new_row);
			if (move_result.swapped_entity != null) {
				EntityLocation swapped_location = entities.get(move_result.swapped_entity);
				archetypes.get(swapped_location.archetype_id).setEntityTableRow(swapped_location.index, old_table_row);
			}
		}
		entity.location = new_location;
		entities.getMeta(entity.id()).location = new_location;
	}

	private static Component take_component(Components components, Storages storages, Archetype archetype, SparseSet<ArrayList<Long>> removed_components_set, int component_id, long entity, EntityLocation location) {
		ComponentInfo component_info = components.getInfo(component_id);
		ArrayList<Long> removed_components = removed_components_set.get_or_insert_with(component_id, ArrayList::new);
		removed_components.add(entity);
		switch(component_info.getStorageType()) {
			case Table:
				Table table = storages.tables.get(archetype.getTableId());
				Column column = table.get_column(component_id);
				int table_row = archetype.getEntityTableRow(location.index);
				return (Component) column.get_data(table_row);
			case SparseSet:
				return (Component) storages.sparse_sets.get(component_id).remove(entity);
		}
		throw new RuntimeException("Unreachable");
	}

	private static Integer remove_bundle_from_archetype(Archetypes archetypes, Storages storages, Components components, int archetype_id, BundleInfo bundle_info, boolean intersection) {
		Archetype current_archetype = archetypes.get(archetype_id);
		Integer remove_bundle_result;
		if (intersection) {
			remove_bundle_result = current_archetype.getEdges().get_remove_bundle_intersection(bundle_info.getId());
		} else {
			remove_bundle_result = current_archetype.getEdges().get_remove_bundle(bundle_info.getId());
		}
		Integer result = remove_bundle_result;
		if (result == null) {
			ArrayList<Integer> removed_table_components = new ArrayList<Integer>();
			ArrayList<Integer> removed_sparse_set_components = new ArrayList<Integer>();
			int[] components_ids = bundle_info.getComponentIds();
			for (int i = 0; i < components_ids.length; i++) {
				int component_id = components_ids[i];
				if (current_archetype.contains(component_id)) {
					switch(components.getInfo(component_id).getStorageType()) {
						case Table:
							removed_table_components.add(component_id);
							break;
						case SparseSet:
							removed_sparse_set_components.add(component_id);
							break;
					}
				} else if (!intersection) {
					current_archetype.getEdges().insert_remove_bundle(bundle_info.getId(), null);
					return null;
				}
			}
			Collections.sort(removed_table_components);
			Collections.sort(removed_sparse_set_components);
			int[] next_table_components = current_archetype.getTableComponents();
			int[] next_sparse_set_components = current_archetype.getSparseSetComponents();
			Entity.sorted_remove(next_table_components, removed_table_components);
			Entity.sorted_remove(next_sparse_set_components, removed_sparse_set_components);
			int next_table_id = current_archetype.getTableId();
			if (removed_table_components.size() > 0) {
				next_table_id = storages.tables.get_id_or_insert(next_table_components, components);
			}
			int new_archetype_id = archetypes.get_id_or_insert(next_table_id, next_table_components, next_sparse_set_components);
			result = new_archetype_id;
		}
		if (intersection) {
			current_archetype.getEdges().insert_remove_bundle_intersection(bundle_info.getId(), result);
		} else {
			current_archetype.getEdges().insert_remove_bundle(bundle_info.getId(), result);
		}
		return result;
	}

	private static int[] sorted_remove(int[] list, List<Integer> removed) {
		return Arrays.stream(list).filter((i) -> {
			return Collections.binarySearch(removed, i) >= 0;
		}).toArray();
	}

	public int id() {
		return Entity.id(this.entity);
	}

	public int generation() {
		return Entity.generation(this.entity);
	}

	public long entity() {
		return this.entity;
	}

	public EntityLocation location() {
		return this.location;
	}

	public Archetype archetype() {
		return this.world.getArchetypes().get(this.location.archetype_id);
	}

	public boolean contains_component(Class<? extends Component> component_class) {
		Integer component_id = world.getComponents().getId(component_class);
		if (component_id != null) {
			return this.contains_component_id(component_id);
		} else {
			return false;
		}
	}

	public boolean contains_component_id(int component_id) {
		return this.world.getArchetypes().get(this.location.archetype_id).contains(component_id);
	}

	@SuppressWarnings("unchecked")
	public <T extends Component> T get(Class<T> component_class) {
		Integer component_id = this.world.getComponents().getId(component_class);
		if (component_id != null) {
			return (T) this.get_component(component_id);
		} else {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends Component> T set(Class<T> component_class) {
		Integer component_id = this.world.getComponents().getId(component_class);
		if (component_id != null) {
			return (T) this.set_component(component_id);
		} else {
			return null;
		}
	}

	public Component get_component(int component_id) {
		Archetype archetype = this.world.getArchetypes().get(this.location.archetype_id);
		ComponentInfo component_info = this.world.getComponents().getInfo(component_id);
		switch(this.world.getComponents().getInfo(component_id).getStorageType()) {
			case Table:
				Table table = this.world.getStorages().tables.get(archetype.getTableId());
				Column column = table.get_column(component_id);
				if (column == null) {
					return null;
				}
				int table_row = archetype.getEntityTableRow(this.location.index);
				return column.data.get(table_row);
			case SparseSet:
				ComponentSparseSet sparse_set = this.world.getStorages().sparse_sets.get(component_id);
				if (sparse_set == null) {
					return null;
				}
				return sparse_set.get(entity);
		}
		throw new RuntimeException("Unreachable");
	}

	public Component set_component(int component_id) {
		Archetype archetype = this.world.getArchetypes().get(this.location.archetype_id);
		switch(this.world.getComponents().getInfo(component_id).getStorageType()) {
			case Table:
				Table table = this.world.getStorages().tables.get(archetype.getTableId());
				Column column = table.get_column(component_id);
				if (column == null) {
					return null;
				}
				int table_row = archetype.getEntityTableRow(this.location.index);
				column.get_ticks(table_row).set_changed(this.world.getChangeTick());
				return column.data.get(table_row);
			case SparseSet:
				ComponentSparseSet sparse_set = this.world.getStorages().sparse_sets.get(component_id);
				if (sparse_set == null) {
					return null;
				}
				sparse_set.get_ticks(entity).set_changed(this.world.getChangeTick());
				return sparse_set.get(entity);
		}
		throw new RuntimeException("Unreachable");
	}

	public <T extends Bundle> Entity insert_bundle(T bundle) {
		int change_tick = this.world.getChangeTick();
		BundleInfo bundle_info = this.world.getBundles().init_info(this.world.getComponents(), this.world.getStorages(), bundle.get_factory());
		BundleInserter bundle_inserter = bundle_info.get_bundle_inserter(this.world.getEntities(), this.world.getArchetypes(), this.world.getComponents(), this.world.getStorages(), this.location.archetype_id, change_tick);
		this.location = bundle_inserter.insert(this.entity, this.location.index, bundle);
		return this;
	}

	public <T extends BundleFactory> Bundle remove_bundle(T bundle_factory) {
		Components components = this.world.getComponents();
		Archetypes archetypes = this.world.getArchetypes();
		Storages storages = this.world.getStorages();
		SparseSet<ArrayList<Long>> removed_components = this.world.getRemovedComponents();
		long entity = this.entity;
		BundleInfo bundle_info = this.world.getBundles().init_info(components, storages, bundle_factory);
		EntityLocation old_location = this.location;
		Integer new_archetype_id = Entity.remove_bundle_from_archetype(archetypes, storages, components, old_location.archetype_id, bundle_info, false);
		if (new_archetype_id == null) {
			return null;
		}
		if (new_archetype_id == old_location.archetype_id) {
			return null;
		}
		Archetype old_archetype = archetypes.get(old_location.archetype_id);
		int[] bundle_components = bundle_info.getComponentIds();
		Entity self = this;
		Bundle result = bundle_factory.from_components(new Supplier<Component>() {
			int i = 0;

			@Override
			public Component get() {
				int component_id = bundle_components[i++];
				return Entity.take_component(components, storages, old_archetype, removed_components, component_id, entity, old_location);
			}
		});
		Entity.move_entity_from_remove(this, this.location, old_location.archetype_id, old_location, this.world.getEntities(), this.world.getArchetypes(), this.world.getStorages(), new_archetype_id, false);
		return result;
	}

	public <T extends Component> Entity insert(T component) {
		Entity.single_b.component = component;
		Entity.single_bf.component_class = component.getClass();
		return this.insert_bundle(Entity.single_b);
	}

	public void remove(Class<? extends Component> component_class) {
		Entity.single_bf.component_class = component_class;
		this.remove_bundle(Entity.single_bf);
	}

	public void despawn() {
		this.world.flush();
		EntityLocation location = this.world.getEntities().free(this.entity);
		if (location == null) {
			throw new RuntimeException("Entity already despawned");
		}
		Archetype archetype = this.world.getArchetypes().get(location.archetype_id);
		ArrayList<Integer> values = archetype.getComponents().getIndices();
		for (int i = 0; i < values.size(); i++) {
			ArrayList<Long> removed_components = this.world.getRemovedComponents().get_or_insert_with(values.get(i), ArrayList::new);
			removed_components.add(this.entity);
		}
		ArchetypeSwapRemoveResult remove_result = archetype.swap_remove(location.index);
		if (remove_result.swapped_entity != null) {
			this.world.getEntities().getMeta(remove_result.swapped_entity).location = location;
		}
		int table_row = remove_result.table_row;
		int[] component_ids = archetype.getSparseSetComponents();
		for (int i = 0; i < component_ids.length; i++) {
			ComponentSparseSet sparse_set = this.world.getStorages().sparse_sets.get(component_ids[i]);
			sparse_set.remove(this.entity);
		}
		Long moved_entity = this.world.getStorages().tables.get(archetype.getTableId()).swap_remove(table_row);
		if (moved_entity != null) {
			EntityLocation moved_location = this.world.getEntities().get(moved_entity);
			this.world.getArchetypes().get(moved_location.archetype_id).setEntityTableRow(moved_location.index, table_row);
		}
	}

	public World getWorld() {
		return world;
	}

	public void update_location() {
		this.location = this.world.getEntities().get(this.entity);
	}

	// Helper private inner classess

	private static final class EntitySingleBundleFactory implements BundleFactory {
		Class<? extends Component> component_class;

		@Override
		public Integer get_unique_id() {
			return Entity.single_id_map.get(this.component_class);
		}

		@Override
		public void set_unique_id(Integer id) {
			Entity.single_id_map.put(this.component_class, id);
		}

		@Override
		public int[] component_ids(Components components, Storages storages) {
			return new int[]{
					components.getId(this.component_class),
			};
		}

		@Override
		public Bundle from_components(Supplier<Component> func) {
			return Entity.single_b;
		}
	}

	private static final class EntitySingleBundle implements Bundle {
		Component component;

		@Override
		public void get_components(Consumer<Component> func) {
			func.accept(this.component);
		}

		@Override
		public BundleFactory get_factory() {
			return Entity.single_bf;
		}
	}
}
