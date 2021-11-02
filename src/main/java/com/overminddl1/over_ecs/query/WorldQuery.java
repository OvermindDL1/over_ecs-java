package com.overminddl1.over_ecs.query;

import com.overminddl1.over_ecs.World;

public interface WorldQuery {
	FetchState init_state(World world);

	Fetch init(World world, FetchState fetch_state, int last_change_tick, int change_tick);
}
