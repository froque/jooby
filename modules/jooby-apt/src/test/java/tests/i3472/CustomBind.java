/*
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package tests.i3472;

import io.jooby.annotation.BindParam;

@BindParam(BeanMapping.class)
public @interface CustomBind {}