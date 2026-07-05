package com.dbf.jdbc.parser;

public enum TokenType {
    EOF(-1),
    
    // Keywords
    SELECT(1),
    FROM(2),
    WHERE(3),
    AND(4),
    OR(5),
    NOT(6),
    ORDER(7),
    BY(8),
    ASC(9),
    DESC(10),
    INSERT(11),
    INTO(12),
    VALUES(13),
    UPDATE(14),
    SET(15),
    DELETE(16),
    CREATE(17),
    DROP(18),
    TABLE(19),
    INDEX(20),
    JOIN(21),
    INNER(22),
    LEFT(23),
    RIGHT(24),
    FULL(25),
    ON(26),
    GROUP(27),
    HAVING(28),
    
    // Aggregate functions - ADD THESE
    COUNT(29),
    SUM(30),
    AVG(31),
    MAX(32),
    MIN(33),
    
    // Operators
    EQ(34),      // =
    LT(35),      // <
    GT(36),      // >
    LE(37),      // <=
    GE(38),      // >=
    NE(39),      // <> or !=
    LIKE(40),
    IN(41),
    BETWEEN(42),
    IS(43),
    NULL(44),
    
    // Literals
    IDENTIFIER(51),
    STRING(52),
    NUMBER(53),
    STAR(54),    // *
    COMMA(55),
    DOT(56),
    LPAREN(57),
    RPAREN(58),
    
    // Arithmetic operators
    PLUS(61),      // +
    MINUS(62),     // -
    MULTIPLY(63),  // *
    DIVIDE(64),    // /
    
    // Special
    RECNO(100),
    UNKNOWN(99);
    
    private final int code;
    
    TokenType(int code) {
        this.code = code;
    }
    
    public int getCode() {
        return code;
    }
    
    public static TokenType fromCode(int code) {
        for (TokenType t : values()) {
            if (t.code == code) return t;
        }
        return UNKNOWN;
    }
}