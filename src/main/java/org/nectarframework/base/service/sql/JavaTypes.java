package org.nectarframework.base.service.sql;

public enum JavaTypes {
	BigDecimal(1), Boolean(2), String(3), Double(4), Float(5), Byte(6), Short(7), Int(8), Long(9), Blob(10), Unknown(11);

	private int typeId;

	JavaTypes(int typeId) {
		this.typeId = typeId;
	}

	public static JavaTypes lookup(int typeId) {
		for (JavaTypes t : JavaTypes.values()) {
			if (t.getTypeId() == typeId) {
				return t;
			}
		}
		return null;
	}

	public int getTypeId() {
		return typeId;
	}

}
