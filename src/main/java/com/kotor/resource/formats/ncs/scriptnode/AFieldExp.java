// Copyright 2021-2025 DeNCS
// Licensed under the MIT License. See LICENSE in the project root for full license text.

package com.kotor.resource.formats.ncs.scriptnode;

import com.kotor.resource.formats.ncs.stack.StackEntry;

/**
 * Expression node for field selection on a non-local aggregate expression, e.g.
 * an action-returned vector: SWMG_GetPosition(OBJECT_SELF).y.
 */
public class AFieldExp extends ScriptNode implements AExpression {
   private AExpression base;
   private String field;
   private StackEntry stackentry;

   public AFieldExp(AExpression base, String field) {
      this.base = base;
      this.field = field;
      if (base != null) {
         base.parent(this);
      }
   }

   @Override
   public String toString() {
      String b = this.base != null ? this.base.toString() : "/*missing*/";
      if (!(this.base instanceof AActionExp) && !(this.base instanceof AVarRef) && !(this.base instanceof AConst)) {
         b = "(" + b + ")";
      }
      return b + "." + this.field;
   }

   @Override
   public StackEntry stackentry() {
      return this.stackentry;
   }

   @Override
   public void stackentry(StackEntry stackentry) {
      this.stackentry = stackentry;
   }

   @Override
   public void close() {
      super.close();
      if (this.base instanceof ScriptNode) {
         ((ScriptNode)this.base).close();
      }
      this.base = null;
      this.field = null;
      this.stackentry = null;
   }
}
