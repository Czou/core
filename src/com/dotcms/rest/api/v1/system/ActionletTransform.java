package com.dotcms.rest.api.v1.system;

import com.dotcms.rest.api.RestTransform;
import com.dotmarketing.portlets.rules.actionlet.RuleActionlet;
import java.util.function.Function;

public class ActionletTransform implements RestTransform<RuleActionlet, RestActionlet> {

    private final Function<RuleActionlet, RestActionlet> toRest = (app) -> {
        RestActionlet rest = new RestActionlet.Builder()
                                     .id(app.getId())
                                     .name(app.getName())
                                     .i18nKey(app.getI18nKey())
                                     .build();

        return rest;
    };

    @Override
    public RuleActionlet applyRestToApp(RestActionlet rest, RuleActionlet app) {
        throw new IllegalStateException("RuleActionlet is not modifiable.");
    }

    @Override
    public RestActionlet appToRest(RuleActionlet app) {
        return toRest.apply(app);
    }

    public Function<RuleActionlet, RestActionlet> appToRestFn() {
        return toRest;
    }
}
