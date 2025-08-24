## 도메인 엔티티 (Domain Entity) = class

도메인 엔티티는 식별자(PK) 기반으로 동일성을 판단해야 하고,  
비즈니스 행위를 포함하므로 data class의 자동 equals/hashCode(내용 기반)와 맞지 않다.  

상태 변경이 가능해야 하는 경우가 많아서 val 위주의 불변 설계인 data class 패턴과도 성격이 다르다.  
equals/hashCode를 직접 구현해서 PK 기반 비교가 되도록 하는 게 안전하다.  

## 도메인 값 객체 (Value Object) = data class

값 객체는 내용이 같으면 동일해야 하고, 변경 불가(불변)로 설계하는 게 이상적 이라고 판단.  
data class의 자동 생성 equals/hashCode가 값 비교에 적합하다.  

## Persistance 엔티티 (Persistence Entity, JPA Entity) = class

JPA는 프록시를 생성할 때 클래스 상속 방식을 쓰기 때문에, data class의 copy()나 구조분해 같은 불필요한 기능이 붙는 게 좋지 않다.  

data class의 equals/hashCode는 모든 프로퍼티를 비교하지만, JPA 엔티티는 PK 기반 비교가 원칙이다.
Hibernate 프록시/지연 로딩 대응을 위해 equals/hashCode를 직접 구현해야 하므로 일반 class가 적합하다.  